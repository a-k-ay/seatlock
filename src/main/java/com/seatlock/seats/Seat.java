package com.seatlock.seats;

import com.seatlock.events.Event;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "seats")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(nullable = false)
    private String section;

    @Column(nullable = false)
    private String row;

    @Column(name = "seat_number", nullable = false)
    private String seatNumber;

    @Column(name = "base_price_amount", nullable = false)
    private Long basePriceAmount;

    @Column(name = "base_price_currency", nullable = false, length = 3)
    private String basePriceCurrency;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}