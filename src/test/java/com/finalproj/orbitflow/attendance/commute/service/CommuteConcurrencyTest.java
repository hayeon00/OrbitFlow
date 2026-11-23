
package com.finalproj.orbitflow.attendance.commute.service;

import com.finalproj.orbitflow.attendance.commute.repository.CommuteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class CommuteConcurrencyTest {

    @Autowired
    private CommuteService commuteService;

    @Autowired
    private CommuteRepository commuteRepository;

    @Test
    void 동시_출근_요청_테스트() throws Exception {

        // 테스트 DB에 존재하는 실제 사원 ID로 변경
        Long companyId = 1L;
        Long employeeId = 1L;
        LocalDate workDate = LocalDate.now();

        int threadCount = 10;

        ExecutorService executor =
                Executors.newFixedThreadPool(threadCount);

        CountDownLatch ready =
                new CountDownLatch(threadCount);

        CountDownLatch start =
                new CountDownLatch(1);

        List<Future<Boolean>> futures =
                new ArrayList<>();

        try {
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();

                    commuteService.checkIn(
                            companyId,
                            employeeId,
                            null
                    );
                    return true;
                }));
            }

            assertTrue(
                    ready.await(5, TimeUnit.SECONDS),
                    "스레드 준비 시간 초과"
            );

            start.countDown();

            int successCount = 0;

            for (Future<Boolean> future : futures) {
                if (future.get(20, TimeUnit.SECONDS)) {
                    successCount++;
                }
            }

            long savedCount =
                    commuteRepository
                            .countByCompanyIdAndEmployeeIdAndWorkDate(
                                    companyId, employeeId, workDate
                            );

            System.out.println("성공 요청 수: " + successCount);
            System.out.println("조회된 출근 기록 수: " + savedCount);

            assertEquals(1, savedCount);

        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}
