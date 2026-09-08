package com.finalproj.orbitflow.global.time.attendance;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Component
public class TimeProvider {

    public LocalDate today() {
        return LocalDate.now();
    }

    public LocalTime nowTime() {
        return LocalTime.now();
    }

    public LocalDateTime nowDateTime() {
        return LocalDateTime.now();
    }
}
