package com.seatlock.events;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;

    @Transactional
    public Event createEvent(CreateEventRequest request) {
        if (!request.endTime().isAfter(request.startTime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "endTime must be after startTime");
        }
        Event event = Event.builder()
                .name(request.name())
                .venue(request.venue())
                .startTime(request.startTime())
                .endTime(request.endTime())
                .status(EventStatus.UPCOMING)
                .build();
        return eventRepository.save(event);
    }

    @Transactional(readOnly = true)
    public Event getEvent(UUID id) {
        return eventRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Event not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<Event> listEvents() {
        return eventRepository.findAll();
    }
}

