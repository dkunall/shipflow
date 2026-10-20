package com.shipflow.cod;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/cod")
@PreAuthorize("hasAnyRole('OPS','ADMIN')")
public class CodController {
    public record ReconcileRequest(@NotBlank String carrierCode, @NotNull Instant from, @NotNull Instant to) {
    }

    private final CodReconciliationService service;

    public CodController(CodReconciliationService service) {
        this.service = service;
    }

    @PostMapping("/remittances")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> remit(@Valid @RequestBody RemittanceRequest request) {
        return Map.of("remittanceId", service.recordRemittance(request));
    }

    @PostMapping("/reconciliations")
    public CodReconciliationService.RunSummary reconcile(@Valid @RequestBody ReconcileRequest r) {
        return service.reconcile(r.carrierCode(), r.from(), r.to());
    }

    @GetMapping("/reconciliations/{runId}")
    public Map<String, Object> get(@PathVariable long runId) {
        return Map.of("summary", service.summary(runId), "results", service.results(runId));
    }
}
