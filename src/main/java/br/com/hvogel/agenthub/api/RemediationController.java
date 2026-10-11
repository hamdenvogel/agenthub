package br.com.hvogel.agenthub.api;

import java.net.URI;
import java.util.List;

import br.com.hvogel.agenthub.exception.RemediationException;
import br.com.hvogel.agenthub.remediation.RemediationProposal;
import br.com.hvogel.agenthub.remediation.RemediationService;
import br.com.hvogel.agenthub.remediation.RemediationService.ConsentStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Correção assistida dos achados: consentimento, propostas (diff), aplicação e reversão. */
@RestController
@RequestMapping("/api/remediation")
public class RemediationController {

    /**
     * @param analysisId análise que contém o achado
     * @param findingId  achado a corrigir
     */
    public record ProposalRequest(String analysisId, String findingId) {
    }

    /** @param confirm o usuário revisou o diff e autoriza a gravação */
    public record ApplyRequest(boolean confirm) {
    }

    private final RemediationService service;

    public RemediationController(RemediationService service) {
        this.service = service;
    }

    @GetMapping("/consent")
    public ConsentStatus consent() {
        return service.consent();
    }

    @PostMapping("/consent")
    public ConsentStatus accept() {
        return service.acceptConsent();
    }

    @DeleteMapping("/consent")
    public ConsentStatus revoke() {
        return service.revokeConsent();
    }

    @GetMapping("/proposals")
    public List<RemediationProposal> list(@RequestParam(required = false) String analysisId) {
        return service.list(analysisId);
    }

    @PostMapping("/proposals")
    public ResponseEntity<RemediationProposal> propose(@RequestBody ProposalRequest request) {
        RemediationProposal proposal = service.propose(request.analysisId(), request.findingId());
        return ResponseEntity.accepted().location(URI.create("/api/remediation/proposals/" + proposal.id())).body(proposal);
    }

    @GetMapping("/proposals/{id}")
    public RemediationProposal get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping("/proposals/{id}/apply")
    public RemediationProposal apply(@PathVariable String id, @RequestBody(required = false) ApplyRequest request) {
        if (request == null || !request.confirm()) {
            throw new RemediationException("Confirme que revisou as alterações (confirm=true) para gravar os arquivos.");
        }
        return service.apply(id);
    }

    @PostMapping("/proposals/{id}/revert")
    public RemediationProposal revert(@PathVariable String id) {
        return service.revert(id);
    }

    @DeleteMapping("/proposals/{id}")
    public ResponseEntity<Void> discard(@PathVariable String id) {
        service.discard(id);
        return ResponseEntity.noContent().build();
    }
}
