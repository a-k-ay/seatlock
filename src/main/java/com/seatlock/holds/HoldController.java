package com.seatlock.holds;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/holds")
@RequiredArgsConstructor
public class HoldController {

    private final HoldService holdService;

    @PostMapping
    public ResponseEntity<HoldResponse> createHold(
            @Valid @RequestBody CreateHoldRequest request) {
        HoldResponse hold = holdService.createHold(request);
        URI location = URI.create("/holds/" + hold.id());
        return ResponseEntity.created(location).body(hold);
    }
}