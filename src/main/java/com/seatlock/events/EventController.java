package com.seatlock.events;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @PostMapping
    public ResponseEntity<EventResponse> createEvent(
            @Valid @RequestBody CreateEventRequest request) {
        Event created = eventService.createEvent(request);
        EventResponse body = EventResponse.from(created);
        URI location = URI.create("/events/" + created.getId());
        return ResponseEntity.created(location).body(body);
    }

    @GetMapping("/{id}")
    public EventResponse getEvent(@PathVariable UUID id) {
        return EventResponse.from(eventService.getEvent(id));
    }

    @GetMapping
    public List<EventResponse> listEvents() {
        return eventService.listEvents().stream()
                .map(EventResponse::from)
                .toList();
    }
}