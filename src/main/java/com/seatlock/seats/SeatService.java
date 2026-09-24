package com.seatlock.seats;

import com.seatlock.events.Event;
import com.seatlock.events.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SeatService {

    private final SeatRepository seatRepository;
    private final EventRepository eventRepository;

    @Transactional
    public List<SeatResponse> createSeats(UUID eventId, CreateSeatsRequest request) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Event not found: " + eventId));

        List<Seat> seats = request.seats().stream()
                .map(req -> Seat.builder()
                        .event(event)
                        .section(req.section())
                        .row(req.row())
                        .seatNumber(req.seatNumber())
                        .basePriceAmount(req.priceAmount())
                        .basePriceCurrency(req.priceCurrency())
                        .build())
                .toList();

        return seatRepository.saveAll(seats).stream()
                .map(SeatResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SeatResponse> listSeats(UUID eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Event not found: " + eventId);
        }
        return seatRepository.findByEventId(eventId).stream()
                .map(SeatResponse::from)
                .toList();
    }
}