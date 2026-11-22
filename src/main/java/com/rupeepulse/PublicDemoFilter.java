package com.rupeepulse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Profile("public-demo")
class PublicDemoFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Referrer-Policy", "no-referrer");
        if (req.getRequestURI().startsWith("/api/")) {
            res.setHeader("Cache-Control", "private, no-store");
            if ("POST".equals(req.getMethod())) {
                long bytes = req.getContentLengthLong();
                if (bytes > 128 * 1024 || (bytes < 0 && (req.getContentType()!=null || req.getHeader("Transfer-Encoding")!=null))) {
                    res.setStatus(bytes < 0 ? 411 : 413);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"error\":\"Use a short, fixed-length request with fictional or redacted data (limit 128 KiB).\"}");
                    return;
                }
            }
        }
        chain.doFilter(req,res);
    }
}
