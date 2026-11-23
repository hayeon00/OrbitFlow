package com.finalproj.orbitflow.attendance.leave.scheduler;

import com.finalproj.orbitflow.attendance.leave.service.LeaveService;
import com.finalproj.orbitflow.attendance.leave.service.RegularLeaveAccrualBatchService;
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
 * Please explain the class!!!
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
    private final LeaveService leaveService;
    private final RegularLeaveAccrualBatchService regularLeaveAccrualBatchService;

    @Value("${orbitflow.leave.regular-accrual-enabled:false}")
    private boolean regularAccrualEnabled;


    /**
     * 입사일 기준 정기 연차 자동 부여.
     *
     * 기존 월별 연차 배치(00:20)와 별도로
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

        LocalDate today =
                LocalDate.now(ZoneId.of("Asia/Seoul"));

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




    @Scheduled(cron = "0 5 0 * * *")
    public void updateDailyWorkStatus() {
        try {
            leaveService.updateAllEmployeesWorkStatus(LocalDate.now());
        } catch (Exception e) {
        }
    }

    @Scheduled(cron = "0 10 0 * * *")
    public void normalizeTodayAttendanceStatus() {
        LocalDate today = LocalDate.now();

        List<Company> companies = companyRepository.findAll();
        for (Company company : companies) {
            try {
                leaveService.normalizeTodayAttendanceStatus(company.getId(), today);
            } catch (Exception e) {
            }
        }
    }


    @Value("${orbitflow.leave.accrual-enabled:false}")
    private boolean accrualEnabled;

    @Scheduled(cron = "0 20 0 * * *", zone = "Asia/Seoul")
    public void runLeaveAccrualBatch() {

        if (!accrualEnabled) {
            return;
        }

        LocalDate today =
                LocalDate.now(ZoneId.of("Asia/Seoul"));

        try {
            leaveService.batchGrantMonthlyLeave(today);

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



}