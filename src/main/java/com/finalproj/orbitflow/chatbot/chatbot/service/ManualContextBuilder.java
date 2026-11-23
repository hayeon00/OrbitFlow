package com.finalproj.orbitflow.chatbot.chatbot.service;

import com.finalproj.orbitflow.chatbot.manual.entity.ManualMetadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 검색된 매뉴얼 청크를 LLM 컨텍스트로 조립하고, LLM 답변의 근거 표기를 검증하는 순수 로직.
 *
 * <ul>
 *   <li>동일 텍스트 청크 제거(같은 파일 중복 업로드 등)</li>
 *   <li>청크를 문서별로 묶고 문서 내 원래 순서(index)로 정렬 → 표 머리글/절차/주의사항의 연결 보존</li>
 *   <li>문서마다 [문서 N] 파일명·등록일 라벨 → 출처 표기 및 문서 간 충돌 인지</li>
 *   <li>LLM 이 답변 끝에 남긴 "근거: [문서 N]" 를 실제 컨텍스트 문서와 대조해 출처를 만든다
 *       (LLM 이 만든 출처 문자열을 그대로 노출하지 않는다)</li>
 * </ul>
 */
final class ManualContextBuilder {

    static final String INDEX_KEY = "index";
    static final String FILE_ID_KEY = "file_id";

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("Asia/Seoul"));
    private static final Pattern CITATION_LINE =
            Pattern.compile("(?m)^\\s*근거\\s*[:：].*$");
    private static final Pattern DOC_REF = Pattern.compile("\\[?문서\\s*(\\d+)\\]?");
    private static final Pattern INLINE_REF = Pattern.compile("\\s*\\[문서\\s*\\d+(\\s*,\\s*\\d+)*\\]");

    private ManualContextBuilder() {
    }

    /** 컨텍스트 안의 문서 하나 */
    record SourceBlock(int number, String fileId, String fileName, String registeredDate, List<TextSegment> segments) {
    }

    /** 사용자에게 돌려줄 최종 답변 */
    record Finalized(String text, List<String> sources, boolean noResult) {
    }

    /** 점수 순서를 유지하면서 텍스트가 같은 청크를 제거하고 상위 k개를 고른다. */
    static List<EmbeddingMatch<TextSegment>> selectTopDistinct(List<EmbeddingMatch<TextSegment>> matches, int k) {
        Set<String> seen = new HashSet<>();
        List<EmbeddingMatch<TextSegment>> out = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> m : matches) {
            if (out.size() >= k) break;
            if (seen.add(normalize(m.embedded().text()))) {
                out.add(m);
            }
        }
        return out;
    }

    /** 선택된 청크의 앞뒤(index ±1) 청크 키 목록. 이미 선택된 청크는 제외. */
    static List<String[]> neighborKeys(List<EmbeddingMatch<TextSegment>> selected) {
        Set<String> selectedKeys = new HashSet<>();
        for (EmbeddingMatch<TextSegment> m : selected) {
            selectedKeys.add(key(m.embedded()));
        }
        Set<String> seen = new HashSet<>();
        List<String[]> out = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> m : selected) {
            String fileId = meta(m.embedded(), FILE_ID_KEY);
            Integer index = indexOf(m.embedded());
            if (fileId == null || index == null) continue;
            for (int d : new int[]{-1, 1}) {
                int idx = index + d;
                if (idx < 0) continue;
                String k = fileId + "#" + idx;
                if (selectedKeys.contains(k) || !seen.add(k)) continue;
                out.add(new String[]{fileId, String.valueOf(idx)});
            }
        }
        return out;
    }

    /**
     * 선택 청크 + 인접 청크를 문서별 블록으로 조립한다.
     * 문서 순서는 해당 문서에서 가장 점수가 높은 청크의 순위, 문서 내부는 원문 순서.
     */
    static List<SourceBlock> build(List<EmbeddingMatch<TextSegment>> selected,
                                   List<TextSegment> neighbors,
                                   Map<String, ManualMetadata> activeByFileId) {
        Map<String, Map<Integer, TextSegment>> byFile = new LinkedHashMap<>();
        Set<String> seenText = new HashSet<>();

        List<TextSegment> ordered = new ArrayList<>();
        selected.forEach(m -> ordered.add(m.embedded()));
        ordered.addAll(neighbors);

        int fallbackIndex = 1_000_000;
        for (TextSegment seg : ordered) {
            String fileId = meta(seg, FILE_ID_KEY);
            if (fileId == null || !activeByFileId.containsKey(fileId)) continue;
            if (!seenText.add(normalize(seg.text()))) continue;
            Integer index = indexOf(seg);
            byFile.computeIfAbsent(fileId, f -> new TreeMap<>())
                    .putIfAbsent(index == null ? fallbackIndex++ : index, seg);
        }

        List<SourceBlock> blocks = new ArrayList<>();
        int n = 1;
        for (Map.Entry<String, Map<Integer, TextSegment>> e : byFile.entrySet()) {
            ManualMetadata manual = activeByFileId.get(e.getKey());
            String date = manual.getCreatedAt() == null ? "알 수 없음" : DATE.format(manual.getCreatedAt());
            blocks.add(new SourceBlock(n++, e.getKey(), manual.getFileName(), date,
                    new ArrayList<>(e.getValue().values())));
        }
        return blocks;
    }

    static String render(List<SourceBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (SourceBlock b : blocks) {
            sb.append("[문서 ").append(b.number()).append("] 파일명: ").append(b.fileName())
                    .append(" | 등록일: ").append(b.registeredDate()).append('\n');
            Integer prev = null;
            for (TextSegment seg : b.segments()) {
                Integer idx = indexOf(seg);
                if (prev != null && (idx == null || idx != prev + 1)) {
                    sb.append("\n(중략)\n");
                } else if (prev != null) {
                    sb.append('\n');
                }
                sb.append(seg.text().trim()).append('\n');
                prev = idx;
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * LLM 원문 답변에서 근거 표기를 분리·검증하고, 실제 컨텍스트에 있는 문서만 출처로 붙인다.
     */
    static Finalized finalizeAnswer(String raw, List<SourceBlock> blocks, String noResultSentence) {
        String text = raw == null ? "" : raw.trim();

        Set<Integer> cited = new LinkedHashSet<>();
        Matcher line = CITATION_LINE.matcher(text);
        while (line.find()) {
            Matcher ref = DOC_REF.matcher(line.group());
            while (ref.find()) cited.add(Integer.parseInt(ref.group(1)));
        }
        text = CITATION_LINE.matcher(text).replaceAll("");
        Matcher inline = Pattern.compile("\\[문서\\s*(\\d+)").matcher(text);
        while (inline.find()) cited.add(Integer.parseInt(inline.group(1)));
        text = INLINE_REF.matcher(text).replaceAll("").trim();

        if (text.isEmpty() || stripPunct(text).equals(stripPunct(noResultSentence))) {
            return new Finalized(noResultSentence, List.of(), true);
        }

        List<String> sources = new ArrayList<>();
        for (Integer n : cited) {
            if (n >= 1 && n <= blocks.size()) {
                String name = blocks.get(n - 1).fileName();
                if (!sources.contains(name)) sources.add(name);
            }
        }
        if (!sources.isEmpty()) {
            text = text + "\n\n출처: " + String.join(", ", sources);
        }
        return new Finalized(text, sources, false);
    }

    static Integer indexOf(TextSegment seg) {
        String v = meta(seg, INDEX_KEY);
        if (v == null) return null;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String key(TextSegment seg) {
        return meta(seg, FILE_ID_KEY) + "#" + meta(seg, INDEX_KEY);
    }

    /** Chroma 에서 읽은 메타데이터는 타입이 섞일 수 있어 문자열로 통일해 읽는다. */
    static String meta(TextSegment seg, String key) {
        Object v = seg.metadata().toMap().get(key);
        return v == null ? null : v.toString();
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private static String stripPunct(String s) {
        return s.replaceAll("[\\s.。!]", "");
    }
}
