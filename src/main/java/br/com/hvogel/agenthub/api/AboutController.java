package br.com.hvogel.agenthub.api;

import br.com.hvogel.agenthub.about.StackInfoService;
import br.com.hvogel.agenthub.about.StackInfoService.StackInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/about")
public class AboutController {

    private final StackInfoService stack;

    public AboutController(StackInfoService stack) {
        this.stack = stack;
    }

    @GetMapping
    public StackInfo about() {
        return stack.info();
    }
}
