
package com.finalproj.orbitflow.attendance.commute.service;

import com.finalproj.orbitflow.attendance.commute.entity.Attendance;
import com.finalproj.orbitflow.attendance.commute.repository.CommuteRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class CommuteCheckOutConcurrencyTest {

    @Autowired
    private CommuteService commuteService;

    @Autowired
    private CommuteRepository commuteRepository;

    @Test
    void 동시_퇴근_요청은_한_번만_성공해야_한다()
            throws Exception {

        // 테스트 DB에 준비한 사원
        Long companyId = 1L;
        Long employeeId = 1L;
        LocalDate workDate = LocalDate.now();

        // 사전에 출근 처리된 기록이 필요
        Attendance before = commuteRepository
                .findByCompanyIdAndEmployeeIdAndWorkDate(
                        companyId, employeeId, workDate
                )
                .orElseThrow();

        assertNull(before.getLeaveAt());

        int threadCount = 10;

        ExecutorService executor =
                Executors.newFixedThreadPool(threadCount);

        CountDownLatch ready =
                new CountDownLatch(threadCount);

        CountDownLatch start =
                new CountDownLatch(1);

        AtomicInteger successCount =
                new AtomicInteger();

        AtomicInteger failCount =
                new AtomicInteger();

        List<Future<?>> futures =
                new ArrayList<>();

        try {
            for (int i = 0; i < threadCount; i++) {

                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();

                    try {
                        commuteService.checkOut(
                                companyId, employeeId
                        );

                        successCount.incrementAndGet();

                    } catch (Exception e) {
                        failCount.incrementAndGet();

                        System.out.println(
                                "퇴근 요청 실패: "
                                        + e.getClass().getSimpleName()
                                        + " / " + e.getMessage()
                        );
                    }

                    return null;
                }));
            }

            assertTrue(
                    ready.await(10, TimeUnit.SECONDS)
            );

            start.countDown();

            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }

            // DB 상태를 새로 조회
            Attendance after = commuteRepository
                    .findByCompanyIdAndEmployeeIdAndWorkDate(
                            companyId, employeeId, workDate
                    )
                    .orElseThrow();

            System.out.println(
                    "성공 요청: " + successCount.get()
            );
            System.out.println(
                    "실패 요청: " + failCount.get()
            );
            System.out.println(
                    "최종 퇴근 시간: " + after.getLeaveAt()
            );

            assertNotNull(after.getLeaveAt());

            // 중복 퇴근을 거부하는 정책을 가정
            assertEquals(1, successCount.get());
            assertEquals(9, failCount.get());

        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}
