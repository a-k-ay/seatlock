package com.seatlock.holds;

import com.seatlock.auth.User;
import com.seatlock.auth.UserRepository;
import com.seatlock.events.Event;
import com.seatlock.events.EventRepository;
import com.seatlock.events.EventStatus;
import com.seatlock.seats.Seat;
import com.seatlock.seats.SeatRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class HoldConcurrencyTest {

    private static final int THREAD_COUNT = 50;

    @Autowired private HoldService holdService;
    @Autowired private HoldRepository holdRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private UserRepository userRepository;

    private Event event;
    private Seat seat;
    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.findByEmail("alice@seatlock.dev").orElseThrow();

        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        event = Event.builder()
                .name("Concurrency Test Event")           // ← name, not title
                .venue("Test Hall")
                .startTime(start)                          // ← startTime, not startsAt
                .endTime(start.plus(3, ChronoUnit.HOURS))  // ← endTime is NOT NULL, must set
                .status(EventStatus.ON_SALE)
                .build();
        event = eventRepository.save(event);

        seat = Seat.builder()
                .event(event)
                .section("A")
                .row("1")
                .seatNumber("1")
                .basePriceAmount(250L)
                .basePriceCurrency("INR")
                .build();
        seat = seatRepository.save(seat);
    }

    @AfterEach
    void cleanUp() {
        holdRepository.deleteAll(holdRepository.findBySeatId(seat.getId()));
        seatRepository.delete(seat);
        eventRepository.delete(event);
        SecurityContextHolder.clearContext();
    }

    @Test
    void only_one_thread_succeeds_when_50_race_for_same_seat() throws InterruptedException {
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(THREAD_COUNT);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        AtomicInteger otherFailureCount = new AtomicInteger(0);

        UUID userId = user.getId();
        UUID seatId = seat.getId();

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    setSecurityContext(userId);
                    holdService.createHold(new CreateHoldRequest(seatId));
                    successCount.incrementAndGet();
                } catch (DataIntegrityViolationException e) {
                    conflictCount.incrementAndGet();
                } catch (Exception e) {
                    otherFailureCount.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    SecurityContextHolder.clearContext();
                    finishGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean finished = finishGate.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).as("All 50 threads should complete within 30s").isTrue();
        assertThat(successCount.get()).as("Exactly one hold succeeds").isEqualTo(1);
        assertThat(conflictCount.get()).as("Other 49 hit the partial unique index").isEqualTo(THREAD_COUNT - 1);
        assertThat(otherFailureCount.get()).as("No unexpected exceptions").isEqualTo(0);

        List<Hold> activeHolds = holdRepository.findByStatusAndSeatIdIn(
                HoldStatus.ACTIVE, List.of(seatId));
        assertThat(activeHolds).as("Only one ACTIVE row for that seat in DB").hasSize(1);
    }

    private void setSecurityContext(UUID userId) {
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                userId,                                                  // ← UUID object directly
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
    }
}