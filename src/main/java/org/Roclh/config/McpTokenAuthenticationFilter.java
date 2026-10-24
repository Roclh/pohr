package org.Roclh.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.Roclh.service.mcp.McpTokenService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Аутентификация /mcp по Bearer-токену.
 *
 * <p>Эндпоинт живёт вне form-login: DSH присылает
 * {@code Authorization: Bearer pohr_...} (или {@code X-Pohr-Mcp-Token}).
 * Без валидного токена отвечаем 401 JSON — иначе Spring Security
 * отредиректил бы агента на /login.</p>
 */
@Component
@RequiredArgsConstructor
public class McpTokenAuthenticationFilter extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = "X-Pohr-Mcp-Token";

    private final McpTokenService tokenService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !("/mcp".equals(path) || "/mcp/".equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String raw = extractToken(request);
        if (raw == null) {
            unauthorized(response, "missing_token",
                    "Передайте Authorization: Bearer <MCP-токен>");
            return;
        }

        Optional<McpTokenService.Principal> principal = tokenService.authenticate(raw);
        if (principal.isEmpty()) {
            unauthorized(response, "invalid_token",
                    "MCP-токен неизвестен, отозван или пользователь не администратор");
            return;
        }

        var authentication = new UsernamePasswordAuthenticationToken(
                principal.get().username(),
                "mcp-token",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        authentication.setDetails(principal.get());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null) {
            String value = authorization.trim();
            if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
                String token = value.substring(7).trim();
                if (!token.isEmpty()) return token;
            }
        }
        String header = request.getHeader(TOKEN_HEADER);
        return header == null || header.isBlank() ? null : header.trim();
    }

    private void unauthorized(HttpServletResponse response, String error, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer realm=\"pohr-mcp\"");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + error + "\",\"error_description\":\"" + message + "\"}");
    }
}
