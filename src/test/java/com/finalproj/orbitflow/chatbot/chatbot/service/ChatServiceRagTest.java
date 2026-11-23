package com.finalproj.orbitflow.chatbot.chatbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalproj.orbitflow.chatbot.chatbot.repository.ChatConversationRepository;
import com.finalproj.orbitflow.chatbot.chatbot.repository.ChatMessageRepository;
import com.finalproj.orbitflow.chatbot.manual.entity.ManualMetadata;
import com.finalproj.orbitflow.chatbot.manual.repository.ManualRepository;
import com.finalproj.orbitflow.chatbot.manualcategory.repository.ManualCategoryRepository;
import com.finalproj.orbitflow.global.file.entity.File;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * ChatService 의 검색 결과 → 컨텍스트 → 답변 처리 규칙 검증 (외부 API 없이).
 */
class ChatServiceRagTest {

    private static final Long COMPANY_ID = 1L;
    private static final Long CATEGORY_ID = 4L;

    private ChatLanguageModel chatModel;
    private EmbeddingStore<TextSegment> store;
    private ManualRepository manualRepository;
    private ChatService chatService;

    private final List<EmbeddingSearchRequest> searchRequests = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chatModel = mock(ChatLanguageModel.class);
        store = mock(EmbeddingStore.class);
        manualRepository = mock(ManualRepository.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[]{1f, 0f})));

        chatService = new ChatService(chatModel, embeddingModel, store,
                mock(ChatConversationRepository.class), mock(ChatMessageRepository.class),
                mock(ManualCategoryRepository.class), manualRepository, new ObjectMapper());
    }

    private static TextSegment seg(String fileId, int index, String text) {
        return TextSegment.from(text, Metadata.from(Map.of(
                "company_id", "1", "category_id", "4", "file_id", fileId, "index", String.valueOf(index))));
    }

    private static EmbeddingMatch<TextSegment> match(double score, TextSegment s) {
        return new EmbeddingMatch<>(score, "id-" + s.text().hashCode(), null, s);
    }

    private static ManualMetadata manual(long fileId, String name) throws Exception {
        ManualMetadata m = ManualMetadata.builder()
                .file(File.builder().id(fileId).build())
                .fileName(name).filePath("k").isActive(true).status("SUCCESS").build();
        Field f = m.getClass().getSuperclass().getDeclaredField("createdAt");
        f.setAccessible(true);
        f.set(m, Instant.parse("2026-03-02T01:00:00Z"));
        return m;
    }

    private void givenActive(ManualMetadata... manuals) {
        when(manualRepository.findByCompanyIdAndCategoryIdAndIsActiveTrueOrderByIdDesc(COMPANY_ID, CATEGORY_ID))
                .thenReturn(List.of(manuals));
    }

    /** 1차 검색 결과와 인접 청크 검색 결과를 순서대로 돌려준다 */
    private void givenSearch(List<EmbeddingMatch<TextSegment>> first, List<EmbeddingMatch<TextSegment>> neighbors) {
        when(store.search(any())).thenAnswer(inv -> {
            searchRequests.add(inv.getArgument(0));
            return new EmbeddingSearchResult<>(searchRequests.size() == 1 ? first : neighbors);
        });
    }

    private String userPrompt() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatModel).generate(captor.capture());
        assertThat(captor.getValue().get(0)).isInstanceOf(SystemMessage.class);
        return ((UserMessage) captor.getValue().get(1)).singleText();
    }

    @Test
    void 활성_매뉴얼이_없으면_검색과_LLM_호출_없이_답변_불가를_반환한다() {
        givenActive();

        String answer = chatService.askQuestion("질문", COMPANY_ID, CATEGORY_ID);

        assertThat(answer).isEqualTo(ChatService.NO_RESULT);
        verifyNoInteractions(store, chatModel);
    }

    @Test
    void 표_행만_검색되어도_머리글이_있는_앞_청크를_함께_원문_순서로_전달한다() throws Exception {
        givenActive(manual(10, "lodging.txt"));
        TextSegment header = seg("10", 1, "| 지역 | 사원 | 대리 | 과장 | 차장 이상 |\n| 서울 | 13 | 14 | 15 | 16 |");
        TextSegment rows = seg("10", 2, "| 안동 | 6 | 7 | 8 | 9 |");
        TextSegment tail = seg("10", 3, "표에 없는 지역은 대전 기준을 적용합니다.");
        givenSearch(List.of(match(0.9, rows)), List.of(match(0.5, tail), match(0.4, header)));
        when(chatModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("9만원입니다.\n근거: [문서 1]")));

        chatService.askQuestion("안동 차장 숙박비?", COMPANY_ID, CATEGORY_ID);

        String prompt = userPrompt();
        assertThat(prompt).contains("[문서 1] 파일명: lodging.txt | 등록일: 2026-03-02");
        assertThat(prompt.indexOf("| 지역 |")).isLessThan(prompt.indexOf("| 안동 |"));
        assertThat(prompt.indexOf("| 안동 |")).isLessThan(prompt.indexOf("대전 기준"));
        // 인접 청크 조회는 같은 회사 범위로 제한된다
        assertThat(searchRequests.get(1).filter().toString()).contains("company_id").contains("index");
    }

    @Test
    void 같은_내용의_중복_청크는_한_번만_전달한다() throws Exception {
        givenActive(manual(10, "a.txt"), manual(11, "a_copy.txt"));
        givenSearch(List.of(
                match(0.9, seg("10", 0, "비밀번호는 90일마다 변경합니다.")),
                match(0.9, seg("11", 0, "비밀번호는 90일마다 변경합니다."))), List.of());
        when(chatModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("90일입니다.\n근거: [문서 1]")));

        chatService.askQuestion("비번 주기?", COMPANY_ID, CATEGORY_ID);

        String prompt = userPrompt();
        assertThat(prompt.split("비밀번호는 90일마다", -1)).hasSize(2);
        assertThat(prompt).doesNotContain("a_copy.txt");
    }

    @Test
    void 비활성_파일의_벡터가_검색되어도_컨텍스트에_넣지_않는다() throws Exception {
        givenActive(manual(10, "active.txt"));
        givenSearch(List.of(
                match(0.95, seg("99", 0, "삭제된 문서: 비밀번호는 30일마다")),
                match(0.80, seg("10", 0, "비밀번호는 90일마다 변경합니다."))), List.of());
        when(chatModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("90일입니다.\n근거: [문서 1]")));

        chatService.askQuestion("비번 주기?", COMPANY_ID, CATEGORY_ID);

        assertThat(userPrompt()).doesNotContain("30일").contains("90일");
    }

    @Test
    void 답변의_근거_문서번호를_실제_파일명_출처로_바꾸고_없는_번호는_무시한다() throws Exception {
        givenActive(manual(10, "travel.txt"));
        givenSearch(List.of(match(0.9, seg("10", 0, "숙박비 상한은 10만원"))), List.of());
        when(chatModel.generate(anyList())).thenReturn(Response.from(
                AiMessage.from("숙박비 상한은 1박 10만원입니다.\n근거: [문서 1], [문서 7]")));

        ChatService.RagAnswer answer = chatService.answer("숙박비?", COMPANY_ID, CATEGORY_ID);

        assertThat(answer.sources()).containsExactly("travel.txt");
        assertThat(answer.text()).isEqualTo("숙박비 상한은 1박 10만원입니다.\n\n출처: travel.txt");
        assertThat(answer.grounded()).isTrue();
    }

    @Test
    void LLM이_답변_불가_문장을_내면_출처를_붙이지_않는다() throws Exception {
        givenActive(manual(10, "travel.txt"));
        givenSearch(List.of(match(0.3, seg("10", 0, "숙박비 상한은 10만원"))), List.of());
        when(chatModel.generate(anyList())).thenReturn(Response.from(
                AiMessage.from("해당 내용은 현재 활성 매뉴얼에서 확인되지 않습니다.\n근거: [문서 1]")));

        ChatService.RagAnswer answer = chatService.answer("법인카드 한도?", COMPANY_ID, CATEGORY_ID);

        assertThat(answer.text()).isEqualTo(ChatService.NO_RESULT);
        assertThat(answer.sources()).isEmpty();
    }

    @Test
    void LLM_호출이_실패하면_예외_대신_오류_안내를_반환한다() throws Exception {
        givenActive(manual(10, "travel.txt"));
        givenSearch(List.of(match(0.9, seg("10", 0, "숙박비 상한은 10만원"))), List.of());
        when(chatModel.generate(anyList())).thenThrow(new RuntimeException("timeout"));

        assertThat(chatService.askQuestion("숙박비?", COMPANY_ID, CATEGORY_ID))
                .isEqualTo(ChatService.ERROR_RESULT);
    }

    @Test
    void 인접_청크_조회가_실패해도_선택된_청크로_답변한다() throws Exception {
        givenActive(manual(10, "travel.txt"));
        when(store.search(any()))
                .thenReturn(new EmbeddingSearchResult<>(List.of(match(0.9, seg("10", 3, "숙박비 상한은 10만원")))))
                .thenThrow(new RuntimeException("chroma"));
        when(chatModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("10만원입니다.\n근거: [문서 1]")));

        assertThat(chatService.askQuestion("숙박비?", COMPANY_ID, CATEGORY_ID)).startsWith("10만원입니다.");
    }
}
