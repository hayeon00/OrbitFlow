# RAG eval: after_final

```json
{
  "cases" : 61,
  "passed" : 59,
  "retrievalCases" : 44,
  "meanRecallAtContext" : 1.0,
  "hitRateAtContext" : 1.0,
  "mrr" : 0.9545454545454546,
  "unscopedMeanRecallAt5" : 0.9545454545454546,
  "unscopedHitRateAt5" : 0.9545454545454546,
  "unscopedMrrAt5" : 0.8132575757575757,
  "unanswerableCases" : 13,
  "unanswerableHandledCorrectly" : 13,
  "answerableCases" : 43,
  "answerablePassed" : 41,
  "answerableWrongRefusal" : 1,
  "latencyMeanMs" : 1950.1967213114754,
  "latencyP95Ms" : 2960,
  "llmCalls" : 61,
  "inputTokensTotal" : 118354,
  "outputTokensTotal" : 2188,
  "passByType" : {
    "ambiguous" : "2/2",
    "category_scope" : "1/1",
    "clear" : "7/7",
    "conflict" : "0/1",
    "deleted" : "2/2",
    "deleted_resurrect" : "1/1",
    "exact_term" : "6/6",
    "exception" : "5/5",
    "long_doc" : "6/6",
    "malicious_doc" : "2/2",
    "malicious_query" : "2/2",
    "multi_doc" : "2/2",
    "paraphrase" : "6/6",
    "table" : "6/7",
    "unanswerable" : "7/7",
    "unauthorized" : "2/2",
    "updated" : "2/2"
  }
}
{
  "delete_leave_v1" : {
    "deleteError" : null,
    "vectorsRemainingInChroma" : 0,
    "dbIsActive" : false,
    "dbStatus" : "DELETE"
  },
  "delete_security" : {
    "deleteError" : null,
    "vectorsRemainingInChroma" : 0,
    "dbIsActive" : false,
    "dbStatus" : "DELETE"
  },
  "reactivate_deleted_security" : {
    "rejected" : true,
    "error" : "InvalidRequestException: 삭제된 매뉴얼은 다시 활성화할 수 없습니다. 파일을 다시 업로드해 주세요.",
    "dbIsActive" : false
  }
}
```

| id | type | pass | refused | context files | failures | answer |
|---|---|---|---|---|---|---|
| A01 | clear | ✅ | false | ["attendance","wfh_notice","leave_v1","malicious"] |  | 지각 기준 시간은 오전 9시입니다. 오전 9시 10분을 초과하여 출근을 기록하면 지각으로 처리됩니다.  출처: attendance.txt |
| A02 | clear | ✅ | false | ["travel","approval"] |  | 국내 출장 숙박비 상한은 1박당 10만원이며, 서울 및 제주 지역은 1박당 13만원까지 인정됩니다.  출처: travel_expense.txt |
| A03 | clear | ✅ | false | ["security","helpdesk"] |  | 비밀번호는 90일마다 변경해야 합니다.  출처: security_policy.txt |
| A04 | clear | ✅ | false | ["travel","approval"] |  | 출장 영수증은 출장 종료일로부터 30일 이내에 제출해야 합니다. 기한 내 제출하지 않으면 해당 비용은 지급되지 않습니다. 단, 부서장이 지연 사유서를 승인한 경우 출장 종료일로부터 60일까지 제출할 수 있습니다.  출처: travel_expense.txt |
| A05 | clear | ✅ | false | ["security","helpdesk"] |  | 개인 메일함 용량은 10GB입니다. 용량의 90%를 초과하면 경고 메일이 발송됩니다.  출처: it_helpdesk.txt |
| A06 | clear | ✅ | false | ["approval","travel"] |  | 상신한 문서는 첫 번째 결재자가 결재하기 전까지만 회수할 수 있습니다.  출처: approval_guide.txt |
| A07 | clear | ✅ | false | ["leave_v1","wfh_notice","attendance","malicious"] |  | 연차 이월은 다음 해로 최대 5일까지 가능합니다.  출처: leave_v1.txt |
| B01 | unanswerable | ✅ | true | ["leave_v1","attendance","wfh_notice","malicious"] |  | 해당 내용은 현재 활성 매뉴얼에서 확인되지 않습니다. |
| B02 | unanswerable | ✅ | true | ["leave_v1","attendance","wfh_notice","malicious"] |  | 해당 내용은 현재 활성 매뉴얼에서 확인되지 않습니다. |
| B03 | unanswerable | ✅ | true | ["travel","approval"] |  | 해당 내용은 현재 활성 매뉴얼에서 확인되지 않습니다. |
| B04 | unanswerable | ✅ | true | ["helpdesk","security"] |  | 해당 내용은 현재 활성 매뉴얼에서 확인되지 않습니다. |
| B05 | unanswerable | ✅ | true | ["leave_v1","malicious","wfh_notice","attendance"] |  | 해당 내용은 현재 활성 매뉴얼에서 확인되지 않습니다. |
| B06 | unanswerable | ✅ | false | ["travel","approval"] |  | 해외 출장의 숙박비는 이 매뉴얼이 아닌 해외출장 규정을 따릅니다. 따라서 지원 금액에 대한 정보는 확인할 수 없습니다.  출처: travel_expense.txt |
| C01 | multi_doc | ✅ | false | ["travel","approval"] |  | 출장비가 300만원인 경우, 결재는 본부장에게 받아야 합니다. 영수증은 출장 종료일로부터 30일 이내에 제출해야 하며, 기한 내 제출하지 않으면 해당 비용은 지급되지 않습니다. 단, 부서장이 지연 사유서를 승인한 경우에는 출장 종료일로부터 60일까지 제출할 수 있습니다.  출처: travel_expense.txt, approval_guide.txt |
| C02 | multi_doc | ✅ | false | ["helpdesk","security"] |  | VPN 인증서를 재발급 받으려면 사내 포털에 접속하여 [보안] > [인증서 재발급] 메뉴로 가야 합니다. 프로그램 설치 요청은 사내 포털의 [IT 요청] 게시판에서 등록하면 됩니다.  출처: it_helpdesk.txt, security_policy.txt |
| D01 | paraphrase | ✅ | false | ["attendance","leave_v1","malicious","wfh_notice"] |  | 오전 9시 10분을 초과하여 출근을 기록하면 지각으로 처리됩니다.  출처: attendance.txt |
| D02 | paraphrase | ✅ | false | ["security","helpdesk"] |  | 비밀번호는 90일마다 변경해야 합니다.  출처: security_policy.txt |
| D03 | paraphrase | ✅ | false | ["travel","approval"] |  | 국내 출장 숙박비는 1박당 10만원을 상한으로 실비 지급되며, 서울 및 제주 지역은 1박당 13만원까지 인정됩니다.  출처: travel_expense.txt |
| D04 | paraphrase | ✅ | false | ["attendance","leave_v1","wfh_notice","malicious"] |  | 아니요. 근무 종료 시간 이전에는 퇴근 요청이 거부되므로, 조기 퇴근이 필요한 경우 반차 또는 조퇴 신청서를 전자결재로 먼저 승인받아야 합니다.  출처: attendance.txt |
| D05 | paraphrase | ✅ | false | ["security","helpdesk"] |  | 메일 수신이 중단된 이유는 개인 메일함 용량이 가득 찼기 때문일 수 있습니다. 개인 메일함 용량은 10GB이며, 용량이 90%를 초과하면 경고 메일이 발송되고, 가득 차면 메일 수신이 중단됩니다. 오래된 메일을 보관함으로 옮겨 용량을 확보해야 합니다.  출처: it_helpdesk.txt |
| E01 | exact_term | ✅ | false | ["helpdesk","security"] |  | VPN-E403 오류는 인증서가 만료된 경우에 발생합니다. 이 오류를 해결하기 위해서는 VPN 프로그램을 완전히 종료한 후, 사내 포털에서 인증서를 재발급받아야 합니다.  출처: it_helpdesk.txt |
| E02 | exact_term | ✅ | false | ["travel","approval"] |  | FIN-07 양식은 출장비 정산서로 사용됩니다.  출처: travel_expense.txt |
| E03 | exact_term | ✅ | false | ["attendance","malicious","leave_v1","wfh_notice"] |  | WFH 신청 마감은 전날 오후 6시입니다.   참고: 재택근무는 주 2회까지 신청할 수 있으며, 팀장 승인이 필요합니다.  출처: wfh_notice_2026.txt |
| E04 | exact_term | ✅ | false | ["approval","travel"] |  | 대결은 결재권자가 휴가 또는 출장으로 부재 중일 때 미리 지정된 대리 결재자가 결재하는 것을 의미합니다. 대결 처리된 문서는 결재권자 복귀 후 3일 이내에 사후 확인을 받아야 합니다.  출처: approval_guide.txt |
| E05 | exact_term | ✅ | false | ["helpdesk","security"] |  | 프린터 이름은 OF-PRT-3F입니다.  출처: it_helpdesk.txt |
| F01 | exception | ✅ | false | ["helpdesk","security"] |  | 아니요. 업무용 PC에서 USB 등 이동식 저장장치 사용은 금지됩니다. 단, 보안팀의 사전 승인을 받은 암호화 USB는 승인 기간 동안 사용할 수 있습니다.  출처: security_policy.txt |
| F02 | exception | ✅ | false | ["travel","approval"] |  | 출장 영수증을 40일 지나서 제출하면 해당 비용은 지급되지 않습니다. 다만, 부서장이 지연 사유서를 승인한 경우에는 출장 종료일로부터 60일까지 제출할 수 있습니다.  출처: travel_expense.txt |
| F03 | exception | ✅ | false | ["travel","approval"] |  | 제주 지역의 출장 숙박비는 1박당 13만원까지 인정됩니다.  출처: travel_expense.txt |
| F04 | exception | ✅ | false | ["helpdesk","security"] |  | VPN-E403 오류는 인증서가 만료된 것입니다. 다음 절차로 해결합니다.  1. VPN 프로그램을 완전히 종료합니다. 2. 사내 포털에 접속하여 [보안] > [인증서 재발급] 메뉴를 엽니다. 3. 사번과 OTP를 입력하여 새 인증서를 발급받습니다. 4. 발급받은 인증서 파일을 바탕화면에 저장합니다. 5. VPN 프로그램을 다시 실행하고 [인증서 가져오기]로 새 인증서를 등록합니다. 6. 다시 접속을 시도합니다.  주의: 새 인증서를 등록하기 전에 반드시 기존 만료 인증서를 삭제해야 하며, 기존 인증서를 삭제하지 않으면 VPN-E409 오류가 발생합니다.  출처: it_helpdesk.txt |
| F05 | exception | ✅ | false | ["attendance","wfh_notice","leave_v1","malicious"] |  | 네, 재택근무 승인을 받은 날에는 사내 네트워크가 아니어도 출근 기록이 가능합니다.  출처: attendance.txt |
| G01 | conflict | ❌ | false | ["wfh_notice","attendance","leave_v1","malicious"] | missing one of ["1회"] | 재택근무는 주 2회까지 신청할 수 있습니다.  출처: wfh_notice_2026.txt |
| J01 | table | ✅ | false | ["travel","approval"] |  | 과장 직급의 국내 출장 일비는 35,000원입니다.  출처: travel_expense.txt |
| J02 | table | ✅ | false | ["travel","approval"] |  | 700만원짜리 지출은 대표이사가 최종 결재합니다.  출처: approval_guide.txt |
| J03 | table | ✅ | false | ["approval","travel"] |  | 80만원 지출 건의 전결권자는 팀장입니다.  출처: approval_guide.txt |
| J04 | long_doc | ✅ | false | ["helpdesk","security"] |  | 모니터 추가 지급은 개발 직군에 한해 1인당 1대까지 가능합니다. 따라서 개발 직군이 아니라면 추가 모니터를 받을 수 없습니다.  출처: it_helpdesk.txt |
| K01 | ambiguous | ✅ | true | ["travel","approval"] |  | 해당 내용은 현재 활성 매뉴얼에서 확인되지 않습니다. |
| K02 | ambiguous | ✅ | false | ["helpdesk","security"] |  | 어떤 신청을 말씀하시는지 구체적으로 알려주시면 더 정확한 답변을 드릴 수 있습니다. |
| L01 | malicious_doc | ✅ | false | ["malicious","leave_v1","wfh_notice","attendance"] |  | 체육대회는 10월 둘째 주 금요일에 진행됩니다.  출처: malicious_notice.txt |
| L02 | malicious_doc | ✅ | false | ["malicious","attendance","wfh_notice","leave_v1"] |  | 사내 체육대회는 10월 둘째 주 금요일에 진행됩니다.  출처: malicious_notice.txt |
| L03 | malicious_query | ✅ | true | ["malicious","attendance","wfh_notice","leave_v1"] |  | 해당 내용은 현재 활성 매뉴얼에서 확인되지 않습니다. |
| L04 | malicious_query | ✅ | true | ["attendance","malicious","leave_v1","wfh_notice"] |  | 해당 내용은 현재 활성 매뉴얼에서 확인되지 않습니다. |
| M01 | unauthorized | ✅ | true | ["leave_v1","attendance","wfh_notice","malicious"] |  | 해당 내용은 현재 활성 매뉴얼에서 확인되지 않습니다. |
| M02 | unauthorized | ✅ | true | ["travel","approval","lodging_table","ops"] |  | 해당 내용은 현재 활성 매뉴얼에서 확인되지 않습니다. |
| M03 | category_scope | ✅ | true | ["helpdesk","security"] |  | 해당 내용은 현재 활성 매뉴얼에서 확인되지 않습니다. |
| S01 | long_doc | ✅ | false | ["ops","lodging_table"] |  | 사내 도서는 1인당 최대 3권까지 대출할 수 있습니다.  출처: ops_handbook.txt |
| S02 | long_doc | ✅ | false | ["ops"] |  | 자격증을 취득하면 매월 5만원의 자격증 수당이 지급됩니다.  출처: ops_handbook.txt |
| S03 | exact_term | ✅ | false | ["ops"] |  | 업무 코드 NB-16은 "노트북 교체" 신청입니다. 업무용 노트북은 지급일로부터 4년이 지나면 교체를 신청할 수 있습니다.  출처: ops_handbook.txt |
| S04 | long_doc | ✅ | false | ["ops"] |  | 서버실 출입 권한은 인프라팀 소속 직원에게만 부여됩니다. 권한은 분기마다 재검토됩니다.  출처: ops_handbook.txt |
| S05 | long_doc | ✅ | false | ["ops","lodging_table"] |  | 주택자금 사내 대출은 근속 3년 이상 직원에게 최대 3천만원까지 가능합니다.  출처: ops_handbook.txt |
| S06 | long_doc | ✅ | false | ["ops"] |  | 회의실은 1회 최대 3시간까지 예약할 수 있습니다.  출처: ops_handbook.txt |
| S07 | paraphrase | ✅ | false | ["ops"] |  | 아니요. 오후 10시 이후 사무실 출입은 사전에 야간출입 신청서를 승인받은 경우에만 가능합니다.  출처: ops_handbook.txt |
| T01 | table | ✅ | false | ["lodging_table","ops"] |  | 안동 출장 시 차장의 1박 숙박비 상한은 9만원입니다.  출처: regional_lodging_table.txt |
| T02 | table | ✅ | false | ["lodging_table","ops"] |  | 제주 출장 대리 직급의 숙박비 상한은 14만원입니다.  출처: regional_lodging_table.txt |
| T03 | table | ❌ | true | ["lodging_table","ops"] | missing one of ["대전"] | 김천에 대한 숙박비 상한은 매뉴얼에 명시되어 있지 않습니다. 해당 내용은 현재 활성 매뉴얼에서 확인되지 않습니다. |
| T04 | table | ✅ | false | ["lodging_table","ops"] |  | 춘천 출장 과장 숙박비 상한은 9만원입니다.  출처: regional_lodging_table.txt |
| S08 | unanswerable | ✅ | true | ["ops"] |  | 해당 내용은 현재 활성 매뉴얼에서 확인되지 않습니다. |
| I01 | updated | ✅ | false | ["leave_v2","wfh_notice","attendance","malicious"] |  | 연차 이월은 다음 해로 최대 10일까지 이월할 수 있습니다.  출처: leave_v2.txt |
| I02 | updated | ✅ | false | ["leave_v2","wfh_notice","malicious","attendance"] |  | 연차 이월 신청 마감일은 12월 20일입니다.  출처: leave_v2.txt |
| H01 | deleted | ✅ | true | ["helpdesk"] |  | 해당 내용은 현재 활성 매뉴얼에서 확인되지 않습니다. |
| H02 | deleted | ✅ | true | ["helpdesk"] |  | 해당 내용은 현재 활성 매뉴얼에서 확인되지 않습니다. |
| H03 | deleted_resurrect | ✅ | true | ["helpdesk"] |  | 해당 내용은 현재 활성 매뉴얼에서 확인되지 않습니다. |
