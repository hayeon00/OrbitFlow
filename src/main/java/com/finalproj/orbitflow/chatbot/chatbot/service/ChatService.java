package com.finalproj.orbitflow.chatbot.chatbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalproj.orbitflow.chatbot.chatbot.dto.ChatConversationListDto;
import com.finalproj.orbitflow.chatbot.chatbot.dto.ChatConversationResponseDto;
import com.finalproj.orbitflow.chatbot.chatbot.dto.ChatMessageDto;
import com.finalproj.orbitflow.chatbot.chatbot.dto.ChatMessageResponseDto;
import com.finalproj.orbitflow.chatbot.chatbot.entity.ChatConversation;
import com.finalproj.orbitflow.chatbot.chatbot.entity.ChatMessage;
import com.finalproj.orbitflow.chatbot.chatbot.repository.ChatConversationRepository;
import com.finalproj.orbitflow.chatbot.chatbot.repository.ChatMessageRepository;
import com.finalproj.orbitflow.chatbot.manual.entity.ManualMetadata;
import com.finalproj.orbitflow.chatbot.manualcategory.entity.ManualCategory;
import com.finalproj.orbitflow.chatbot.manualcategory.repository.ManualCategoryRepository;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * Please explain the class!!!
 *
 * @author : rlagkdus
 * @filename : ChatService
 * @since : 2025. 12. 30. 화요일
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

        // === 기존 RAG 의존성 ===
        private final ChatLanguageModel chatLanguageModel;
        private final EmbeddingModel embeddingModel;
        private final EmbeddingStore<TextSegment> embeddingStore;

        // === 추가: 대화 저장용 ===
        private final ChatConversationRepository conversationRepository;
        private final ChatMessageRepository messageRepository;
        private final ManualCategoryRepository manualCategoryRepository;
        private final com.finalproj.orbitflow.chatbot.manual.repository.ManualRepository manualRepository;

        private final ObjectMapper objectMapper;

        private static final String NO_RESULT =
                "해당 내용은 현재 활성 매뉴얼에서 확인되지 않습니다.";

        @Transactional
        public ChatConversationResponseDto createConversation(Long companyId, Long employeeId, Long manualCategoryId) {

                Long categoryId = null;
                String categoryNameSnapshot = null;

                if (manualCategoryId != null) {
                        ManualCategory category = manualCategoryRepository
                                        .findByIdAndCompanyIdAndIsActiveTrue(manualCategoryId, companyId)
                                        .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 매뉴얼 카테고리입니다."));

                        categoryId = category.getId();
                        categoryNameSnapshot = category.getCategoryName(); // 컬럼명/게터는 프로젝트에 맞게
                }

                ChatConversation saved = conversationRepository.save(
                                ChatConversation.builder()
                                                .companyId(companyId)
                                                .employeeId(employeeId)
                                                .manualCategoryId(categoryId)
                                                .manualCategoryName(categoryNameSnapshot)
                                                .status(ChatConversation.Status.ACTIVE)
                                                .deleted(false)
                                                .build());

                return ChatConversationResponseDto.builder()
                                .conversationId(saved.getId())
                                .build();
        }

        @Transactional(readOnly = true)
        public List<ChatConversationListDto> listConversations(Long companyId, Long employeeId) {
                return conversationRepository
                                .findTop20ByCompanyIdAndEmployeeIdAndDeletedFalseOrderByUpdatedAtDesc(companyId,
                                                employeeId)
                                .stream()
                                .map(c -> ChatConversationListDto.builder()
                                                .conversationId(c.getId())
                                                .manualCategoryId(c.getManualCategoryId())
                                                .manualCategoryName(c.getManualCategoryName())
                                                .title(c.getTitle())
                                                .status(c.getStatus().name())
                                                .updatedAt(c.getUpdatedAt()) // BaseEntity(Instant)
                                                .build())
                                .toList();
        }

        @Transactional(readOnly = true)
        public List<ChatMessageDto> getMessages(Long companyId, Long employeeId, Long conversationId) {
                ChatConversation conv = conversationRepository
                                .findByIdAndCompanyIdAndEmployeeIdAndDeletedFalse(conversationId, companyId, employeeId)
                                .orElseThrow(() -> new IllegalArgumentException("대화방이 없거나 권한이 없습니다."));

                return messageRepository.findByConversationIdOrderByCreatedAtAscIdAsc(conv.getId())
                                .stream()
                                .map(m -> ChatMessageDto.builder()
                                                .messageId(m.getId())
                                                .role(m.getRole().name())
                                                .content(m.getContent())
                                                .meta(m.getMetaJson())
                                                .createdAt(m.getCreatedAt()) // BaseEntity(Instant)
                                                .build())
                                .toList();
        }

        @Transactional
        public ChatMessageResponseDto sendMessage(Long companyId, Long employeeId, Long conversationId,
                        String content) {

                if (content == null || content.isBlank()) {
                        throw new IllegalArgumentException("content는 필수입니다.");
                }

                ChatConversation conv = conversationRepository
                                .findByIdAndCompanyIdAndEmployeeIdAndDeletedFalse(conversationId, companyId, employeeId)
                                .orElseThrow(() -> new IllegalArgumentException("대화방이 없거나 권한이 없습니다."));

                messageRepository.save(ChatMessage.builder()
                                .companyId(companyId)
                                .conversationId(conv.getId())
                                .role(ChatMessage.Role.USER)
                                .content(content)
                                .metaJson(null)
                                .build());

                Long categoryId = conv.getManualCategoryId();
                String answerText = askQuestion(content, companyId, categoryId);

                if (NO_RESULT.equals(answerText)) {
                    log.info("매뉴얼 미존재 응답 - companyId={}, conversationId={}, categoryId={}",
                            companyId, conversationId, categoryId);
                }

                HashMap<String, Object> metaMap = new HashMap<>();
                metaMap.put("companyId", companyId);
                metaMap.put("categoryId", categoryId);
                JsonNode metaJson = objectMapper.valueToTree(metaMap);

                ChatMessage savedAssistant = messageRepository.save(ChatMessage.builder()
                                .companyId(companyId)
                                .conversationId(conv.getId())
                                .role(ChatMessage.Role.ASSISTANT)
                                .content(answerText)
                                .metaJson(metaJson)
                                .build());


                conv.setTitleIfEmpty(trimTo255(content));

                return ChatMessageResponseDto.builder()
                                .assistant(ChatMessageDto.builder()
                                                .messageId(savedAssistant.getId())
                                                .role(savedAssistant.getRole().name())
                                                .content(savedAssistant.getContent())
                                                .meta(savedAssistant.getMetaJson())
                                                .createdAt(savedAssistant.getCreatedAt())
                                                .build())
                                .build();
        }

        private String trimTo255(String s) {
                String t = s.trim();
                return t.length() > 255 ? t.substring(0, 255) : t;
        }


        public String askQuestion(String question, Long companyId, Long categoryId) {

            long retrievalStart = System.currentTimeMillis();

            var questionEmbedding = embeddingModel.embed(question).content();

            List<ManualMetadata> activeManuals = (categoryId != null)
                    ? manualRepository.findByCompanyIdAndCategoryIdAndIsActiveTrueOrderByIdDesc(
                    companyId, categoryId)
                    : manualRepository.findAllByCompanyIdAndIsActiveTrueOrderByIdDesc(companyId);

            Set<String> activeFileIdSet = activeManuals.stream()
                    .map(m -> m.getFile().getId().toString())
                    .collect(Collectors.toSet());

            String companyIdStr = companyId.toString();
            String categoryIdStr = categoryId == null
                    ? null
                    : categoryId.toString();

            Filter filter = metadataKey("company_id").isEqualTo(companyIdStr);

            if (categoryIdStr != null) {
                filter = filter.and(
                        metadataKey("category_id").isEqualTo(categoryIdStr)
                );
            }

            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(questionEmbedding)

                    // 아직 튜닝하지 않는다.
                    .maxResults(50)
                    .minScore(0.0)

                    .filter(filter)
                    .build();

            EmbeddingSearchResult<TextSegment> result =
                    embeddingStore.search(request);

            List<EmbeddingMatch<TextSegment>> matches = result.matches();

            log.info(
                    "[RAG_SEARCH] question=\"{}\", companyId={}, categoryId={}, rawMatches={}",
                    question,
                    companyId,
                    categoryId,
                    matches.size()
            );

            // 로그가 너무 많아지지 않도록 상위 10개만 출력
            int logCount = Math.min(matches.size(), 10);

            for (int i = 0; i < logCount; i++) {

                EmbeddingMatch<TextSegment> match = matches.get(i);

                var metadata = match.embedded()
                        .metadata()
                        .toMap();

                Object storedFileId = metadata.get("file_id");

                String fileId = storedFileId == null
                        ? null
                        : storedFileId.toString();

                boolean active = fileId != null
                        && activeFileIdSet.contains(fileId);

                String text = match.embedded().text();

                String preview = text.length() > 200
                        ? text.substring(0, 200)
                        : text;

                log.info(
                        "[RAG_MATCH] rank={}, score={}, fileId={}, active={}, text=\"{}\"",
                        i + 1,
                        match.score(),
                        fileId,
                        active,
                        preview.replace("\n", " ")
                );
            }

            List<EmbeddingMatch<TextSegment>> activeMatches =
                    matches.stream()
                            .filter(m -> {

                                Object storedFileId = m.embedded()
                                        .metadata()
                                        .toMap()
                                        .get("file_id");

                                return storedFileId != null
                                        && activeFileIdSet.contains(
                                        storedFileId.toString()
                                );
                            })
                            .toList();

            String context = activeMatches.stream()
                    .map(m -> m.embedded().text())
                    .collect(Collectors.joining("\n\n"));

            long retrievalMs =
                    System.currentTimeMillis() - retrievalStart;

            log.info(
                    "[RAG_RESULT] question=\"{}\", activeMatches={}, contextLength={}, retrievalMs={}",
                    question,
                    activeMatches.size(),
                    context.length(),
                    retrievalMs
            );

            if (context.isBlank()) {
                return NO_RESULT;
            }


            String prompt = String.format(
                    """
                    당신은 OrbitFlow 사내 매뉴얼 안내 챗봇입니다.
            
                    반드시 아래 규칙을 지켜 답변하세요.
            
                    [최우선 규칙]
            
                    1. 오직 아래 [매뉴얼 내용]에 직접 명시된 정보만 사용합니다.
            
                    2. 매뉴얼에 직접 명시되지 않은 정보는
                       상식, 경험, 일반적인 회사 규칙, 추론을 이용하여 보충하지 않습니다.
            
                    3. 다음 정보는 매뉴얼에 명확하게 적혀 있는 경우에만 답변합니다.
            
                       - 사용 가능 대상
                       - 사용자 권한
                       - 공개 범위
                       - 처리 가능 시간
                       - 처리 제한 시간
                       - 버튼 활성화/비활성화 조건
                       - 데이터 반영 시간
                       - 데이터 갱신 주기
                       - 검색 필터 존재 여부
                       - 오류 발생 원인
                       - 오류 해결 방법
                       - 승인/반려/취소 조건
                       - 알림 방식
                       - 자동 처리 여부
                       - 예외 상황
                       - 특정 기능의 제한 개수
                       - 화면에 표시되는 메시지
            
                    4. 질문에 대한 답을 매뉴얼에서 확인할 수 있으면
                       확인되는 내용만 간결하게 답변합니다.
            
                    5. 일부 정보만 확인되는 경우
                       확인되는 부분만 답변하고,
                       확인되지 않는 부분은 추측하지 않습니다.
            
                    6. 질문의 핵심 내용을 매뉴얼에서 확인할 수 없는 경우에는
                       반드시 다음 문장으로 답변을 시작합니다.
            
                       "해당 내용은 현재 활성 매뉴얼에서 확인되지 않습니다."
            
                       그 뒤에도 추측성 설명을 추가하지 않습니다.
            
                    7. 매뉴얼에 없는 팁, 권장 행동, 문제 해결 방법,
                       추가 조건을 임의로 만들어내지 않습니다.
            
                    8. 사용자에게 추가 질문을 억지로 하지 않습니다.
                       현재 질문에 답하는 데 꼭 필요한 경우에만 추가 질문을 합니다.
            
                    9. 이전 대화에서 Assistant가 말한 내용은 사실 근거로 사용하지 않습니다.
            
                    10. 답변을 길게 만들기 위해 내용을 추가하지 않습니다.
                        짧고 정확한 답변을 우선합니다.
            
            
                    [매뉴얼 내용]
            
                    %s
            
            
                    [사용자 질문]
            
                    %s
            
            
                    [답변 형식]
            
                    질문에 대한 직접적인 답변부터 작성합니다.
            
                    매뉴얼에 이동 경로가 명확하게 있는 경우에만 다음 형식을 추가합니다.
            
                    이용 경로:
                    {매뉴얼에 적힌 경로}
            
                    추가로 반드시 알아야 할 내용이 매뉴얼에 명시되어 있는 경우에만 다음을 추가합니다.
            
                    참고:
                    {매뉴얼에 실제로 존재하는 내용}
            
                    근거가 없는 경우에는
                    "이용 경로" 또는 "참고" 항목 자체를 작성하지 않습니다.
            
                    같은 내용을 반복하지 않습니다.
            
                    존댓말을 사용하되 간결하게 답변합니다.
            
            
                    답변:
                    """,
                    context,
                    question
            );


            return chatLanguageModel.generate(prompt);
        }
}