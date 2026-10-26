package com.centry.export;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDate;

/** Admin-only CSV download; the rule lives in {@code SecurityConfig}. */
@RestController
class ExpenseExportController {

    private static final int MAX_YEARS = 5;

    private final ExpenseExport export;

    ExpenseExportController(ExpenseExport export) {
        this.export = export;
    }

    @GetMapping("/export/expenses.csv")
    void download(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletResponse response) throws IOException {
        if (from.isAfter(to) || to.isAfter(from.plusYears(MAX_YEARS))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "from must not be after to, and the range is at most " + MAX_YEARS + " years");
        }
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"centry-expenses-" + from + "-to-" + to + ".csv\"");
        export.write(from, to, response.getWriter());
    }
}
