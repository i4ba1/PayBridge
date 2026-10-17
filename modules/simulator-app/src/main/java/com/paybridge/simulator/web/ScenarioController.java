package com.paybridge.simulator.web;

import com.paybridge.simulator.service.ApiException;
import com.paybridge.simulator.service.ChargeService;
import com.paybridge.simulator.service.ScenarioService;
import com.paybridge.simulator.service.ScenarioService.CreateRun;
import com.paybridge.simulator.service.ScenarioService.Run;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Scenario runs and the operator listing: private, bearer protected, never routed publicly (API contract 8.3). */
@RestController
public class ScenarioController {
    private final ScenarioService scenarios;
    private final ChargeService charges;

    public ScenarioController(ScenarioService scenarios, ChargeService charges) {
        this.scenarios = scenarios;
        this.charges = charges;
    }

    @PostMapping("/internal/scenario-runs")
    public ResponseEntity<Run> create(@RequestBody CreateRun body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(scenarios.create(body));
    }

    @GetMapping("/internal/scenario-runs/{runId}")
    public Run get(@PathVariable String runId) {
        return scenarios.get(runId).orElseThrow(() -> new ApiException(404, "run_not_found", "No such scenario run"));
    }

    @PostMapping("/internal/scenario-runs/{runId}/deactivate")
    public Run deactivate(@PathVariable String runId) {
        return scenarios.deactivate(runId);
    }

    @GetMapping("/internal/operations")
    public ChargeService.OperationsPage operations(@RequestParam(required = false) String from,
                                                   @RequestParam(required = false) String to,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String cursor,
                                                   @RequestParam(required = false) Integer limit) {
        return charges.operations(instant(from, "from"), instant(to, "to"), status, cursor, limit);
    }

    private static Instant instant(String value, String name) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw ApiException.invalid(name + " must be an ISO-8601 UTC timestamp");
        }
    }
}
