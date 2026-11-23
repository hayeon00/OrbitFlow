
package com.finalproj.orbitflow.attendance.commute.service;

import com.finalproj.orbitflow.approval.attendance.event.repository.AttendanceEventRepository;
import com.finalproj.orbitflow.approval.attendance.record.repository.AttendanceRecordRepository;
import com.finalproj.orbitflow.attendance.commute.repository.CommuteRepository;
import com.finalproj.orbitflow.attendance.leave.service.LeaveTimePolicyService;
import com.finalproj.orbitflow.attendance.rule.repository.AttendanceRuleRepository;
import com.finalproj.orbitflow.attendance.rule.repository.EmployeeRuleRepository;
import com.finalproj.orbitflow.global.exception.BusinessException;
import com.finalproj.orbitflow.global.time.attendance.TimeProvider;
import com.finalproj.orbitflow.hr.employee.repository.EmployeeRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommuteServiceFullDayWorkEventTest {

    private static final Long COMPANY_ID = 1L;
    private static final Long EMPLOYEE_ID = 10L;

    private static final LocalDate WORK_DATE =
            LocalDate.of(2026, 11, 3);

    private static final LocalDateTime CHECK_IN_TIME =
            WORK_DATE.atTime(9, 0);

    private static final LocalDateTime CHECK_OUT_TIME =
            WORK_DATE.atTime(18, 0);

    @Mock
    private CommuteRepository commuteRepository;

    @Mock
    private AttendanceRuleRepository attendanceRuleRepository;

    @Mock
    private EmployeeRuleRepository employeeRuleRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private AttendanceEventRepository attendanceEventRepository;

    @Mock
    private AttendanceRecordRepository attendanceRecordRepository;

    @Mock
    private LeaveTimePolicyService leaveTimePolicyService;

    @Mock
    private TimeProvider timeProvider;

    @InjectMocks
    private CommuteService commuteService;

    private void mockApprovedEvent(LocalDate date) {

        when(attendanceEventRepository
                .existsApprovedFullDayWorkEvent(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        date
                ))
                .thenReturn(true);
    }

    private void assertAttendanceNotChanged() {

        verifyNoInteractions(commuteRepository);

        verifyNoInteractions(employeeRepository);
    }

    /**
     * 1. 승인된 출장일에는 출근을 차단한다.
     */
    @Test
    void 승인된_출장일에는_출근할수없다() {

        when(timeProvider.nowDateTime())
                .thenReturn(CHECK_IN_TIME);

        mockApprovedEvent(WORK_DATE);

        assertThrows(
                BusinessException.class,
                () -> commuteService.checkIn(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        null
                )
        );

        verify(attendanceEventRepository)
                .existsApprovedFullDayWorkEvent(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        WORK_DATE
                );

        assertAttendanceNotChanged();
    }

    /**
     * 2. 승인된 외근일에도 출근을 차단한다.
     */
    @Test
    void 승인된_종일외근일에는_출근할수없다() {

        when(timeProvider.nowDateTime())
                .thenReturn(CHECK_IN_TIME);

        mockApprovedEvent(WORK_DATE);

        assertThrows(
                BusinessException.class,
                () -> commuteService.checkIn(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        null
                )
        );

        assertAttendanceNotChanged();
    }

    /**
     * 3. 승인된 출장·외근일에는 퇴근을 차단한다.
     */
    @Test
    void 승인된_출장외근일에는_퇴근할수없다() {

        when(timeProvider.nowDateTime())
                .thenReturn(CHECK_OUT_TIME);

        mockApprovedEvent(WORK_DATE);

        assertThrows(
                BusinessException.class,
                () -> commuteService.checkOut(
                        COMPANY_ID,
                        EMPLOYEE_ID
                )
        );

        assertAttendanceNotChanged();
    }

    /**
     * 4. 승인된 출장·외근일에는
     * 자리비움 상태로 변경하지 않는다.
     */
    @Test
    void 승인된_출장외근일에는_자리비움할수없다() {

        when(timeProvider.today())
                .thenReturn(WORK_DATE);

        mockApprovedEvent(WORK_DATE);

        assertThrows(
                BusinessException.class,
                () -> commuteService.startAway(
                        COMPANY_ID,
                        EMPLOYEE_ID
                )
        );

        assertAttendanceNotChanged();
    }

    /**
     * 5. 종료된 출장·외근은 차단 대상으로
     * 인식하지 않아야 한다.
     *
     * 조회 결과가 false이면
     * 일반 출퇴근 검증 단계로 진입하는지 확인한다.
     */
    @Test
    void 출장외근_기간종료후에는_일반출근검증으로_진행한다() {

        LocalDate nextDay =
                LocalDate.of(2026, 11, 5);

        LocalDateTime nextDayTime =
                nextDay.atTime(9, 0);

        when(timeProvider.nowDateTime())
                .thenReturn(nextDayTime);

        when(attendanceEventRepository
                .existsApprovedFullDayWorkEvent(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        nextDay
                ))
                .thenReturn(false);

        // 실제 출퇴근 처리의 다음 단계로 진입하도록
        // 테스트용 근무 규칙 조회 결과를 준비한다.
        when(employeeRuleRepository
                .findActiveRuleByEmployeeIdAndDate(
                        EMPLOYEE_ID,
                        nextDay
                ))
                .thenReturn(java.util.Optional.empty());

        when(attendanceRuleRepository
                .findByCompanyIdAndIsDefaultTrue(
                        COMPANY_ID
                ))
                .thenReturn(java.util.Optional.empty());

        // 출퇴근 차단을 통과하면 다음 단계에서
        // 승인 반차를 조회한다.
        // 그 단계에 도달했는지 확인하기 위해
        // 반차 조회에서 예외를 발생시킨다.
        RuntimeException marker =
                new IllegalStateException(
                        "일반 출근 검증 단계에 진입함"
                );

        when(attendanceRecordRepository
                .findOverlappingRecords(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        com.finalproj.orbitflow.approval.document.enums.DocumentStatus.APPROVED,
                        nextDay,
                        nextDay
                ))
                .thenThrow(marker);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> commuteService.checkIn(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        null
                )
        );

        assertSame(marker, thrown);

        verify(attendanceEventRepository)
                .existsApprovedFullDayWorkEvent(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        nextDay
                );

        verify(attendanceRecordRepository)
                .findOverlappingRecords(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        com.finalproj.orbitflow.approval.document.enums.DocumentStatus.APPROVED,
                        nextDay,
                        nextDay
                );

        verifyNoInteractions(commuteRepository);
    }

    /**
     * 6. 승인된 출장·외근이 없다면
     * 자리비움 요청을 일반 근태 검증까지 진행한다.
     */
    @Test
    void 승인된_출장외근이_없으면_자리비움_근태검증을_실행한다() {

        when(timeProvider.today())
                .thenReturn(WORK_DATE);

        when(attendanceEventRepository
                .existsApprovedFullDayWorkEvent(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        WORK_DATE
                ))
                .thenReturn(false);

        // 출퇴근 차단을 통과했다면
        // 근태 기록 유효성 검증에서 Repository를 조회한다.
        // 기록이 없는 상태이므로 최종 요청은 실패해도
        // 차단 로직을 통과했다는 사실은 검증할 수 있다.
        assertThrows(
                RuntimeException.class,
                () -> commuteService.startAway(
                        COMPANY_ID,
                        EMPLOYEE_ID
                )
        );

        verify(commuteRepository, atLeastOnce())
                .findByCompanyIdAndEmployeeIdAndWorkDate(
                        COMPANY_ID,
                        EMPLOYEE_ID,
                        WORK_DATE
                );
    }
}
