package com.finalproj.orbitflow.chatbot.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finalproj.orbitflow.chatbot.chatbot.repository.ChatConversationRepository;
import com.finalproj.orbitflow.chatbot.chatbot.repository.ChatMessageRepository;
import com.finalproj.orbitflow.chatbot.chatbot.service.ChatService;
import com.finalproj.orbitflow.chatbot.chorma.service.ChromaVectorService;
import com.finalproj.orbitflow.chatbot.manual.entity.ManualMetadata;
import com.finalproj.orbitflow.chatbot.manual.repository.ManualRepository;
import com.finalproj.orbitflow.chatbot.manual.service.ManualUploadService;
import com.finalproj.orbitflow.chatbot.manualcategory.entity.ManualCategory;
import com.finalproj.orbitflow.chatbot.manualcategory.repository.ManualCategoryRepository;
import com.finalproj.orbitflow.global.file.entity.File;
import com.finalproj.orbitflow.global.file.service.FileService;
import com.finalproj.orbitflow.hr.company.entity.Company;
import com.finalproj.orbitflow.hr.employee.entity.Employee;
import com.finalproj.orbitflow.hr.employee.repository.EmployeeRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 사내 매뉴얼 RAG 품질 평가 하네스.
 *
 * <p>실제 업로드/청킹/임베딩(ManualUploadService)과 실제 검색/프롬프트/LLM(ChatService.askQuestion)
 * 코드 경로를 그대로 사용하고, MySQL/S3 만 메모리 대체물로 바꾼다.
 * OpenAI API(비용 발생)와 로컬 Chroma 가 필요하므로 기본 빌드에서는 실행되지 않는다.</p>
 *
 * <pre>
 * docker run -d --name of-chroma-eval -p 18000:8000 -e IS_PERSISTENT=TRUE \
 *   chromadb/chroma:0.4.21 uvicorn chromadb.app:app --host 0.0.0.0 --port 8000
 * RAG_EVAL=true RAG_EVAL_LABEL=after ./gradlew test --tests '*ManualRagEvaluationTest*'
 * </pre>
 * 결과: build/rag-eval/{label}.json, build/rag-eval/{label}.md
 */
@EnabledIfEnvironmentVariable(named = "RAG_EVAL", matches = "true")
class ManualRagEvaluationTest {

    private static final String REFUSAL = "확인되지 않습니다";
    private final ObjectMapper om = new ObjectMapper();

    // ---- in-memory "DB" ----
    private final List<ManualMetadata> manuals = new ArrayList<>();
    private final Map<String, ManualMetadata> manualByKey = new HashMap<>();
    private final Map<String, String> keyByFileId = new HashMap<>();
    private final AtomicLong fileSeq = new AtomicLong(100);
    private final AtomicLong manualSeq = new AtomicLong(1);
    private final Map<Long, Employee> employeeByCompany = new HashMap<>();
    private final Map<Long, Company> companies = new HashMap<>();
    private final Map<Long, ManualCategory> categoryById = new HashMap<>();
    private final Map<Long, Long> categoryCompany = new HashMap<>();

    private RecordingStore store;
    private String collectionName;
    private EmbeddingModel embeddingModelRef;
    private RecordingChatModel chatModel;
    private ManualUploadService uploadService;
    private ChatService chatService;

    @Test
    void evaluate() throws Exception {
        String label = Optional.ofNullable(System.getenv("RAG_EVAL_LABEL")).orElse("run");
        String chromaUrl = Optional.ofNullable(System.getenv("RAG_EVAL_CHROMA_URL")).orElse("http://localhost:18000");
        String apiKey = loadApiKey();

        JsonNode spec = om.readTree(resource("rag-eval/cases.json"));
        Map<String, Long> categoryIds = new HashMap<>();
        spec.get("categories").fields().forEachRemaining(e -> categoryIds.put(e.getKey(), e.getValue().asLong()));

        EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
                .apiKey(apiKey).modelName("text-embedding-3-small").build();
        ChatLanguageModel realChat = OpenAiChatModel.builder()
                .apiKey(apiKey).modelName("gpt-4o-mini").temperature(0.0).build();
        collectionName = "orbitflow_eval_" + label + "_" + System.currentTimeMillis();
        EmbeddingStore<TextSegment> chroma = ChromaEmbeddingStore.builder()
                .baseUrl(chromaUrl)
                .collectionName(collectionName)
                .timeout(Duration.ofSeconds(30)).build();

        embeddingModelRef = embeddingModel;
        store = new RecordingStore(chroma);
        chatModel = new RecordingChatModel(realChat);
        wire(embeddingModel, chromaUrl, categoryIds);

        // ---- initial uploads ----
        for (JsonNode u : spec.get("uploads")) {
            upload(u, categoryIds);
        }

        ArrayNode results = om.createArrayNode();
        ObjectNode syncChecks = om.createObjectNode();

        for (JsonNode phase : spec.get("phases")) {
            String phaseName = phase.get("name").asText();
            for (JsonNode action : phase.get("actions")) {
                applyAction(action, categoryIds, syncChecks, embeddingModel);
            }
            for (JsonNode c : spec.get("cases")) {
                if (!phaseName.equals(c.get("phase").asText())) continue;
                results.add(runCase(c, categoryIds));
            }
        }

        ObjectNode report = om.createObjectNode();
        report.put("label", label);
        report.put("runAt", Instant.now().toString());
        report.set("summary", summarize(results));
        report.set("sync", syncChecks);
        report.set("cases", results);

        Path out = Path.of("build/rag-eval");
        Files.createDirectories(out);
        om.writerWithDefaultPrettyPrinter().writeValue(out.resolve(label + ".json").toFile(), report);
        Files.writeString(out.resolve(label + ".md"), markdown(report));
        System.out.println(markdown(report));
    }

    // ------------------------------------------------------------------ wiring

    private void wire(EmbeddingModel embeddingModel, String chromaUrl, Map<String, Long> categoryIds) throws Exception {
        for (Long companyId : List.of(1L, 2L)) {
            Company company = mock(Company.class);
            when(company.getId()).thenReturn(companyId);
            Employee employee = mock(Employee.class);
            when(employee.getCompany()).thenReturn(company);
            companies.put(companyId, company);
            employeeByCompany.put(companyId, employee);
        }
        categoryIds.forEach((name, id) -> {
            ManualCategory cat = mock(ManualCategory.class);
            when(cat.getId()).thenReturn(id);
            when(cat.getCategoryName()).thenReturn(name);
            categoryById.put(id, cat);
            categoryCompany.put(id, name.startsWith("B_") ? 2L : 1L);
        });

        EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
        when(employeeRepository.findById(anyLong())).thenAnswer(inv ->
                Optional.ofNullable(employeeByCompany.get(inv.<Long>getArgument(0))));

        ManualCategoryRepository categoryRepository = mock(ManualCategoryRepository.class);
        when(categoryRepository.findByIdAndCompanyIdAndIsActiveTrue(anyLong(), anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            Long companyId = inv.getArgument(1);
            return companyId.equals(categoryCompany.get(id)) ? Optional.of(categoryById.get(id)) : Optional.empty();
        });

        FileService fileService = mock(FileService.class);
        when(fileService.upload(anyLong(), any(), any())).thenAnswer(inv -> {
            long id = fileSeq.incrementAndGet();
            return File.builder().id(id).objectKey("eval/" + id).build();
        });

        ManualRepository manualRepository = inMemoryManualRepository();

        ChromaVectorService vectorService = EvalWiring.vectorService(store, chromaUrl, collectionName);

        uploadService = EvalWiring.uploadService(manualRepository, categoryRepository, employeeRepository,
                fileService, embeddingModel, store, vectorService);

        chatService = new ChatService(chatModel, embeddingModel, store,
                mock(ChatConversationRepository.class), mock(ChatMessageRepository.class),
                categoryRepository, manualRepository, om);
    }

    private ManualRepository inMemoryManualRepository() {
        ManualRepository repo = mock(ManualRepository.class);
        when(repo.save(any())).thenAnswer(inv -> {
            ManualMetadata m = inv.getArgument(0);
            if (m.getId() == null) {
                setField(m, "id", manualSeq.getAndIncrement());
                setField(m, "createdAt", Instant.now());
                manuals.add(m);
            }
            return m;
        });
        when(repo.findById(anyLong())).thenAnswer(inv -> manuals.stream()
                .filter(m -> m.getId().equals(inv.getArgument(0))).findFirst());
        when(repo.findByIdAndCompanyId(anyLong(), anyLong())).thenAnswer(inv -> manuals.stream()
                .filter(m -> m.getId().equals(inv.getArgument(0))
                        && m.getCompany().getId().equals(inv.getArgument(1))).findFirst());
        when(repo.findAllByCompanyIdAndIsActiveTrueOrderByIdDesc(anyLong())).thenAnswer(inv -> manuals.stream()
                .filter(m -> m.getIsActive() && m.getCompany().getId().equals(inv.getArgument(0)))
                .sorted(Comparator.comparing(ManualMetadata::getId).reversed()).toList());
        when(repo.findByCompanyIdAndCategoryIdAndIsActiveTrueOrderByIdDesc(anyLong(), anyLong())).thenAnswer(inv -> manuals.stream()
                .filter(m -> m.getIsActive() && m.getCompany().getId().equals(inv.getArgument(0))
                        && m.getCategory().getId().equals(inv.getArgument(1)))
                .sorted(Comparator.comparing(ManualMetadata::getId).reversed()).toList());
        return repo;
    }

    // ------------------------------------------------------------------ actions

    private void upload(JsonNode u, Map<String, Long> categoryIds) throws Exception {
        String key = u.get("key").asText();
        String fileName = u.get("file").asText();
        long companyId = u.get("company").asLong();
        long categoryId = categoryIds.get(u.get("category").asText());
        byte[] bytes = resource("rag-eval/manuals/" + fileName).readAllBytes();

        int before = manuals.size();
        uploadService.uploadAndIndexingManual(
                new MockMultipartFile("file", fileName, "text/plain", bytes), categoryId, companyId);
        ManualMetadata saved = manuals.get(before);
        // ManualMetadata 의 company/category 는 mock 엔티티로 교체 (JPA 연관관계 대체)
        setField(saved, "company", companies.get(companyId));
        manualByKey.put(key, saved);
        keyByFileId.put(saved.getFile().getId().toString(), key);
    }

    private void applyAction(JsonNode action, Map<String, Long> categoryIds, ObjectNode sync,
                             EmbeddingModel embeddingModel) throws Exception {
        String op = action.get("op").asText();
        String key = action.get("key").asText();
        switch (op) {
            case "upload" -> upload(action, categoryIds);
            case "delete" -> {
                ManualMetadata m = manualByKey.get(key);
                String error = null;
                try {
                    uploadService.deleteManual(m.getId(), m.getCompany().getId());
                } catch (Exception e) {
                    error = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                int remaining = countVectors(m, embeddingModel);
                ObjectNode n = sync.putObject("delete_" + key);
                n.put("deleteError", error);
                n.put("vectorsRemainingInChroma", remaining);
                n.put("dbIsActive", m.getIsActive());
                n.put("dbStatus", m.getStatus());
            }
            case "reactivate" -> {
                ManualMetadata m = manualByKey.get(key);
                String error = null;
                try {
                    uploadService.updateManualActive(m.getCompany().getId(), m.getId(), true);
                } catch (Exception e) {
                    error = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                ObjectNode n = sync.putObject("reactivate_deleted_" + key);
                n.put("rejected", error != null);
                n.put("error", error);
                n.put("dbIsActive", m.getIsActive());
            }
            default -> throw new IllegalArgumentException(op);
        }
    }

    private int countVectors(ManualMetadata m, EmbeddingModel embeddingModel) {
        Embedding probe = embeddingModel.embed("매뉴얼").content();
        Filter f = metadataKey("file_id").isEqualTo(m.getFile().getId().toString());
        return store.delegate.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(probe).maxResults(100).minScore(0.0).filter(f).build()).matches().size();
    }

    // ------------------------------------------------------------------ evaluation

    private ObjectNode runCase(JsonNode c, Map<String, Long> categoryIds) {
        String question = c.get("question").asText();
        Long categoryId = c.get("category").isNull() ? null : categoryIds.get(c.get("category").asText());

        store.last = null;
        chatModel.lastPrompt = null;
        chatModel.lastResponse = null;

        long start = System.nanoTime();
        String answer;
        String error = null;
        try {
            answer = chatService.askQuestion(question, 1L, categoryId);
        } catch (Exception e) {
            answer = "";
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        long latencyMs = (System.nanoTime() - start) / 1_000_000;

        String prompt = Optional.ofNullable(chatModel.lastPrompt).orElse("");
        List<EmbeddingMatch<TextSegment>> raw = store.last == null ? List.of() : store.last.matches();

        // 실제 LLM 컨텍스트에 들어간 청크의 파일
        Set<String> contextFiles = new LinkedHashSet<>();
        List<String> rankedFiles = new ArrayList<>();
        List<String> contextChunks = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> m : raw) {
            String key = keyByFileId.getOrDefault(String.valueOf(m.embedded().metadata().toMap().get("file_id")), "?");
            rankedFiles.add(key);
            if (!prompt.isEmpty() && prompt.contains(m.embedded().text())) {
                contextFiles.add(key);
                contextChunks.add(key + "#" + m.embedded().metadata().toMap().get("index"));
            }
        }

        ObjectNode r = om.createObjectNode();
        r.put("id", c.get("id").asText());
        r.put("type", c.get("type").asText());
        r.put("phase", c.get("phase").asText());
        r.put("question", question);
        r.put("answer", answer);
        r.put("error", error);
        r.put("latencyMs", latencyMs);
        r.put("llmCalled", chatModel.lastResponse != null);
        if (chatModel.lastResponse != null && chatModel.lastResponse.tokenUsage() != null) {
            r.put("inputTokens", chatModel.lastResponse.tokenUsage().inputTokenCount());
            r.put("outputTokens", chatModel.lastResponse.tokenUsage().outputTokenCount());
        }
        r.put("contextChars", prompt.length());
        r.set("contextFiles", om.valueToTree(contextFiles));
        r.set("contextChunks", om.valueToTree(contextChunks));
        r.set("rankedFilesTop10", om.valueToTree(rankedFiles.stream().limit(10).toList()));

        List<String> failures = new ArrayList<>();
        boolean refused = answer.contains(REFUSAL);
        r.put("refused", refused);

        // --- retrieval ---
        if (c.has("expectFiles")) {
            List<String> expected = om.convertValue(c.get("expectFiles"), List.class);
            long hit = expected.stream().filter(contextFiles::contains).count();
            r.put("recallAtContext", (double) hit / expected.size());
            int firstRank = 0;
            for (int i = 0; i < rankedFiles.size(); i++) {
                if (expected.contains(rankedFiles.get(i))) { firstRank = i + 1; break; }
            }
            r.put("reciprocalRank", firstRank == 0 ? 0.0 : 1.0 / firstRank);
        }
        if (c.has("expectFiles")) {
            // 카테고리 제한 없이 회사 1의 전체 활성 매뉴얼에서 top-5 검색 (검색 부하 테스트)
            List<String> expected = om.convertValue(c.get("expectFiles"), List.class);
            List<String> top5 = unscopedTop5(question);
            r.set("unscopedTop5", om.valueToTree(top5));
            long hit = expected.stream().filter(top5::contains).count();
            r.put("unscopedRecallAt5", (double) hit / expected.size());
            int rank = 0;
            for (int i = 0; i < top5.size(); i++) if (expected.contains(top5.get(i))) { rank = i + 1; break; }
            r.put("unscopedRR", rank == 0 ? 0.0 : 1.0 / rank);
        }
        if (c.has("keyFact")) {
            boolean inContext = prompt.contains(c.get("keyFact").asText());
            r.put("keyFactInContext", inContext);
            if (!inContext) failures.add("keyFact not in LLM context");
        }
        if (c.has("forbiddenFiles")) {
            List<String> forbidden = om.convertValue(c.get("forbiddenFiles"), List.class);
            boolean leaked = rankedFiles.stream().anyMatch(forbidden::contains);
            r.put("forbiddenRetrieved", leaked);
            if (leaked) failures.add("forbidden file retrieved");
        }

        // --- answer ---
        if (c.path("expectRefusal").asBoolean(false) && !refused) failures.add("expected refusal");
        if (c.has("mustInclude")) {
            for (JsonNode group : c.get("mustInclude")) {
                boolean any = false;
                for (JsonNode v : group) any |= answer.contains(v.asText());
                if (!any) failures.add("missing one of " + group);
            }
        }
        if (c.has("expectRefusalOrInclude")) {
            boolean ok = refused;
            for (JsonNode group : c.get("expectRefusalOrInclude")) {
                for (JsonNode v : group) ok |= answer.contains(v.asText());
            }
            if (!ok) failures.add("neither refusal nor " + c.get("expectRefusalOrInclude"));
        }
        if (c.path("expectRefusalOrClarify").asBoolean(false)) {
            boolean clarify = answer.contains("?") || answer.contains("구체적") || answer.contains("어떤");
            if (!refused && !clarify) failures.add("answered ambiguous question without clarification");
        }
        if (c.has("mustNotInclude")) {
            for (JsonNode v : c.get("mustNotInclude")) {
                if (answer.contains(v.asText())) failures.add("contains forbidden '" + v.asText() + "'");
            }
        }
        if (error != null) failures.add("error: " + error);

        r.put("pass", failures.isEmpty());
        r.set("failures", om.valueToTree(failures));
        return r;
    }

    private List<String> unscopedTop5(String question) {
        Filter f = metadataKey("company_id").isEqualTo("1");
        Filter active = null;
        for (ManualMetadata m : manuals) {
            if (!m.getIsActive() || !m.getCompany().getId().equals(1L)) continue;
            Filter one = metadataKey("file_id").isEqualTo(m.getFile().getId().toString());
            active = active == null ? one : active.or(one);
        }
        f = f.and(active);
        return store.delegate.search(EmbeddingSearchRequest.builder()
                        .queryEmbedding(embeddingModelRef.embed(question).content())
                        .maxResults(5).minScore(0.0).filter(f).build())
                .matches().stream()
                .map(m -> keyByFileId.getOrDefault(String.valueOf(m.embedded().metadata().toMap().get("file_id")), "?"))
                .toList();
    }

    private ObjectNode summarize(ArrayNode results) {
        ObjectNode s = om.createObjectNode();
        List<JsonNode> all = new ArrayList<>();
        results.forEach(all::add);
        s.put("cases", all.size());
        s.put("passed", all.stream().filter(r -> r.get("pass").asBoolean()).count());

        List<JsonNode> withRecall = all.stream().filter(r -> r.has("recallAtContext")).toList();
        s.put("retrievalCases", withRecall.size());
        s.put("meanRecallAtContext", withRecall.stream().mapToDouble(r -> r.get("recallAtContext").asDouble()).average().orElse(0));
        s.put("hitRateAtContext", withRecall.stream().filter(r -> r.get("recallAtContext").asDouble() > 0).count() / (double) Math.max(1, withRecall.size()));
        s.put("mrr", withRecall.stream().mapToDouble(r -> r.get("reciprocalRank").asDouble()).average().orElse(0));
        s.put("unscopedMeanRecallAt5", withRecall.stream().mapToDouble(r -> r.get("unscopedRecallAt5").asDouble()).average().orElse(0));
        s.put("unscopedHitRateAt5", withRecall.stream().filter(r -> r.get("unscopedRecallAt5").asDouble() > 0).count() / (double) Math.max(1, withRecall.size()));
        s.put("unscopedMrrAt5", withRecall.stream().mapToDouble(r -> r.get("unscopedRR").asDouble()).average().orElse(0));

        Set<String> refusalTypes = Set.of("unanswerable", "deleted", "deleted_resurrect", "unauthorized", "category_scope");
        List<JsonNode> mustRefuse = all.stream().filter(r -> refusalTypes.contains(r.get("type").asText())).toList();
        s.put("unanswerableCases", mustRefuse.size());
        s.put("unanswerableHandledCorrectly", mustRefuse.stream().filter(r -> r.get("pass").asBoolean()).count());

        List<JsonNode> answerable = all.stream().filter(r -> r.has("recallAtContext") && !refusalTypes.contains(r.get("type").asText())).toList();
        s.put("answerableCases", answerable.size());
        s.put("answerablePassed", answerable.stream().filter(r -> r.get("pass").asBoolean()).count());
        s.put("answerableWrongRefusal", answerable.stream().filter(r -> r.get("refused").asBoolean()).count());

        long[] lat = all.stream().mapToLong(r -> r.get("latencyMs").asLong()).sorted().toArray();
        s.put("latencyMeanMs", Arrays.stream(lat).average().orElse(0));
        s.put("latencyP95Ms", lat.length == 0 ? 0 : lat[(int) Math.ceil(lat.length * 0.95) - 1]);
        s.put("llmCalls", all.stream().filter(r -> r.get("llmCalled").asBoolean()).count());
        s.put("inputTokensTotal", all.stream().mapToLong(r -> r.path("inputTokens").asLong(0)).sum());
        s.put("outputTokensTotal", all.stream().mapToLong(r -> r.path("outputTokens").asLong(0)).sum());

        ObjectNode byType = s.putObject("passByType");
        all.stream().collect(Collectors.groupingBy(r -> r.get("type").asText(), TreeMap::new, Collectors.toList()))
                .forEach((t, list) -> byType.put(t, list.stream().filter(r -> r.get("pass").asBoolean()).count() + "/" + list.size()));
        return s;
    }

    private String markdown(ObjectNode report) {
        StringBuilder sb = new StringBuilder("# RAG eval: ").append(report.get("label").asText()).append("\n\n");
        sb.append("```json\n").append(report.get("summary").toPrettyString()).append("\n")
                .append(report.get("sync").toPrettyString()).append("\n```\n\n");
        sb.append("| id | type | pass | refused | context files | failures | answer |\n|---|---|---|---|---|---|---|\n");
        for (JsonNode r : report.get("cases")) {
            sb.append("| ").append(r.get("id").asText())
                    .append(" | ").append(r.get("type").asText())
                    .append(" | ").append(r.get("pass").asBoolean() ? "✅" : "❌")
                    .append(" | ").append(r.get("refused").asBoolean())
                    .append(" | ").append(r.get("contextFiles"))
                    .append(" | ").append(String.join("; ", om.convertValue(r.get("failures"), List.class)))
                    .append(" | ").append(r.get("answer").asText().replace("\n", " ").replace("|", "/"))
                    .append(" |\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ utils

    private static InputStream resource(String path) {
        InputStream in = ManualRagEvaluationTest.class.getClassLoader().getResourceAsStream(path);
        if (in == null) throw new IllegalStateException("missing resource " + path);
        return in;
    }

    private static String loadApiKey() throws Exception {
        String env = System.getenv("OPENAI_API_KEY");
        if (env != null && !env.isBlank()) return env;
        Path dotenv = Path.of(".env");
        if (Files.exists(dotenv)) {
            for (String line : Files.readAllLines(dotenv, StandardCharsets.UTF_8)) {
                if (line.startsWith("OPENAI_API_KEY=")) return line.substring("OPENAI_API_KEY=".length()).trim();
            }
        }
        throw new IllegalStateException("OPENAI_API_KEY not set");
    }

    static void setField(Object target, String name, Object value) {
        try {
            Class<?> c = target.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    f.set(target, value);
                    return;
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
            throw new IllegalArgumentException(name);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /** 검색 결과를 기록하는 EmbeddingStore 데코레이터 */
    static class RecordingStore implements EmbeddingStore<TextSegment> {
        final EmbeddingStore<TextSegment> delegate;
        volatile EmbeddingSearchResult<TextSegment> last;

        RecordingStore(EmbeddingStore<TextSegment> delegate) { this.delegate = delegate; }

        @Override public String add(Embedding embedding) { return delegate.add(embedding); }
        @Override public void add(String id, Embedding embedding) { delegate.add(id, embedding); }
        @Override public String add(Embedding embedding, TextSegment segment) { return delegate.add(embedding, segment); }
        @Override public List<String> addAll(List<Embedding> embeddings) { return delegate.addAll(embeddings); }
        @Override public List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments) { return delegate.addAll(embeddings, segments); }
        @Override public void removeAll(Filter filter) { delegate.removeAll(filter); }
        @Override public void removeAll(Collection<String> ids) { delegate.removeAll(ids); }
        @Override public void removeAll() { delegate.removeAll(); }

        @Override
        public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
            EmbeddingSearchResult<TextSegment> result = delegate.search(request);
            if (last == null) {
                last = result; // 질문당 첫 번째(본) 검색만 기록. 이후 인접 청크 조회는 제외
            }
            return result;
        }
    }

    /** LLM 에 전달된 프롬프트와 토큰 사용량을 기록하는 데코레이터 */
    static class RecordingChatModel implements ChatLanguageModel {
        final ChatLanguageModel delegate;
        volatile String lastPrompt;
        volatile Response<AiMessage> lastResponse;

        RecordingChatModel(ChatLanguageModel delegate) { this.delegate = delegate; }

        @Override
        public Response<AiMessage> generate(List<ChatMessage> messages) {
            lastPrompt = messages.stream().map(m -> {
                if (m instanceof dev.langchain4j.data.message.UserMessage um) return um.singleText();
                if (m instanceof dev.langchain4j.data.message.SystemMessage sm) return sm.text();
                return m.toString();
            }).collect(Collectors.joining("\n"));
            lastResponse = delegate.generate(messages);
            return lastResponse;
        }
    }
}
