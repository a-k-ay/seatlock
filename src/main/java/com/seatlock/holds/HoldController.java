package com.seatlock.holds;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.fasterxml.jackson.core.type.TypeReference;

import java.net.URI;
import java.util.Optional;

import java.util.List;

@RestController
@RequestMapping("/holds")
@RequiredArgsConstructor
public class HoldController {

    private final HoldService holdService;
    private final IdempotencyStore idempotencyStore;
    private final ObjectMapper objectMapper;

    @PostMapping
    public ResponseEntity<HoldResponse> createHold(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateHoldRequest request) {

        HoldResponse hold;

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String requestHash = idempotencyStore.hash(toJson(request));
            Optional<IdempotencyStore.Cached> cached = idempotencyStore.get(idempotencyKey);

            if (cached.isPresent()) {
                if (!cached.get().requestHash().equals(requestHash)) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "IDEMPOTENCY_KEY_REUSED");
                }
                hold = fromJson(cached.get().responseJson());
            } else {
                hold = holdService.createHold(request);
                idempotencyStore.store(idempotencyKey, requestHash, toJson(hold));
            }
        } else {
            hold = holdService.createHold(request);
        }

        URI location = URI.create("/holds/" + hold.id());
        return ResponseEntity.created(location).body(hold);
    }

    @PostMapping("/bulk")
    public ResponseEntity<List<HoldResponse>> createBulkHolds(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateBulkHoldRequest request) {

        List<HoldResponse> holds;

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String requestHash = idempotencyStore.hash(toJson(request));
            Optional<IdempotencyStore.Cached> cached = idempotencyStore.get(idempotencyKey);

            if (cached.isPresent()) {
                if (!cached.get().requestHash().equals(requestHash)) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "IDEMPOTENCY_KEY_REUSED");
                }
                holds = fromJsonList(cached.get().responseJson());
            } else {
                holds = holdService.createBulkHolds(request);
                idempotencyStore.store(idempotencyKey, requestHash, toJson(holds));
            }
        } else {
            holds = holdService.createBulkHolds(request);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(holds);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancelHold(@PathVariable UUID id) {
        holdService.cancelHold(id);
        return ResponseEntity.noContent().build();
    }    

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON serialization failed", e);
        }
    }

    private HoldResponse fromJson(String json) {
        try {
            return objectMapper.readValue(json, HoldResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON deserialization failed", e);
        }
    }
    
    private List<HoldResponse> fromJsonList(String json) {
    try {
        return objectMapper.readValue(json, new TypeReference<List<HoldResponse>>() {});
    } catch (JsonProcessingException e) {
        throw new IllegalStateException("JSON deserialization failed", e);
    }
}


}