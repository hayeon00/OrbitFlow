package com.finalproj.orbitflow.attendance.leave.scheduler;

import com.finalproj.orbitflow.attendance.leave.service.LeaveWorkStatusSyncService;
import com.finalproj.orbitflow.attendance.leave.service.accrual.MonthlyLeaveAccrualBatchService;
import com.finalproj.orbitflow.attendance.leave.service.accrual.RegularLeaveAccrualBatchService;
import com.finalproj.orbitflow.hr.company.entity.Company;
import com.finalproj.orbitflow.hr.company.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 연차·근태 일일 배치 트리거.
 *
 * 00:05 근무상태 동기화 → 00:10 일일 근태 반영
 * → 00:20 월별 연차 → 00:25 정기 연차 (모두 Asia/Seoul)
 *
 * 시간·설정 플래그·실패 로깅만 담당하고 실제 처리는 각 서비스에 위임한다.
 *
 * @author : rlagkdus
 * @filename : LeaveScheduler
 * @since : 2025. 12. 24. 수요일
 */

@Component
@RequiredArgsConstructor
@Slf4j
public class LeaveScheduler {

    private final CompanyRepository companyRepository;
    private final MonthlyLeaveAccrualBatchService monthlyLeaveAccrualBatchService;
    private final RegularLeaveAccrualBatchService regularLeaveAccrualBatchService;
    private final LeaveWorkStatusSyncService leaveWorkStatusSyncService;


    @Value("${orbitflow.leave.accrual-enabled:false}")
    private boolean accrualEnabled;

    @Value("${orbitflow.leave.regular-accrual-enabled:false}")
    private boolean regularAccrualEnabled;


    /**
     * 입사일 기준 1년미만 신입사원 연차 자동 부여.
     *
     * 매일 한국 시간 00:20에 실행한다.
     */
    @Scheduled(
            cron = "0 20 0 * * *",
            zone = "Asia/Seoul"
    )
    public void runLeaveAccrualBatch() {

        if (!accrualEnabled) {
            return;
        }

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

        try {
            monthlyLeaveAccrualBatchService.run(today);

            log.info(
                    "[연차 발생 배치 완료] date={}",
                    today
            );

        } catch (Exception e) {
            log.error(
                    "[연차 발생 배치 실패] date={}",
                    today,
                    e
            );
        }
    }


    /**
     * 입사일 기준 1년 이상 정기 연차 자동 부여.
     *
     * 매일 한국 시간 00:25에 실행한다.
     */
    @Scheduled(
            cron = "0 25 0 * * *",
            zone = "Asia/Seoul"
    )
    public void runRegularLeaveAccrualBatch() {

        if (!regularAccrualEnabled) {
            return;
        }

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

        try {
            regularLeaveAccrualBatchService.run(today);

            log.info(
                    "[정기 연차 자동 부여] 실행 완료 - today={}",
                    today
            );

        } catch (Exception e) {
            log.error(
                    "[정기 연차 자동 부여] 배치 오류 - today={}",
                    today,
                    e
            );
        }
    }




    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Seoul")
    public void updateDailyWorkStatus() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

        try {
            leaveWorkStatusSyncService.updateAllEmployeesWorkStatus(today);
        } catch (Exception e) {
            log.error("[근무상태 동기화] 배치 오류 - today={}", today, e);
        }
    }

    @Scheduled(cron = "0 10 0 * * *", zone = "Asia/Seoul")
    public void normalizeTodayAttendanceStatus() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

        List<Company> companies = companyRepository.findAll();
        for (Company company : companies) {
            try {
                leaveWorkStatusSyncService.normalizeTodayAttendanceStatus(company.getId(), today);
            } catch (Exception e) {
                log.error(
                        "[일일 근태 반영] 회사별 오류 - companyId={}, today={}",
                        company.getId(),
                        today,
                        e
                );
            }
        }
    }






}