package org.Roclh.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Редирект на форму логина для браузерных путей. */
    private static final LoginUrlAuthenticationEntryPoint LOGIN_ENTRY_POINT =
            new LoginUrlAuthenticationEntryPoint("/login");

    private final McpTokenAuthenticationFilter mcpTokenAuthenticationFilter;

    public SecurityConfig(McpTokenAuthenticationFilter mcpTokenAuthenticationFilter) {
        this.mcpTokenAuthenticationFilter = mcpTokenAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/css/**", "/js/**").permitAll()
                        .requestMatchers("/sub/**").permitAll()
                        .requestMatchers("/invite/**").permitAll()
                        // Периметр узлов: только те endpoint'ы, где контроллер сам проверяет
                        // enroll-токен (query) или X-Node-Secret (header). Всё остальное под
                        // /api/nodes/** — админское, чтобы каталог узлов не отвечал анонимно.
                        .requestMatchers("/api/nodes/register",
                                "/api/nodes/bootstrap.sh",
                                "/api/nodes/agent.sh").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/nodes/*/config").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/nodes/*/health").permitAll()
                        .requestMatchers("/api/nodes/**").hasRole("ADMIN")
                        .requestMatchers("/mcp", "/mcp/", "/mcp/**").hasRole("ADMIN")
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/ws/**").authenticated()
                        .anyRequest().authenticated()
                )
                // Машинные пути не должны отдавать HTML-логин: анонимный запрос
                // получает 401, а не 302 на /login — иначе сам редирект выдаёт
                // панель. Браузерные пути, наоборот, уходят на страницу входа.
                .exceptionHandling(entry -> entry.authenticationEntryPoint(SecurityConfig::commenceEntryPoint))
                .csrf(csrf -> csrf.ignoringRequestMatchers(
                        "/api/**", "/sub/**", "/mcp", "/mcp/", "/mcp/**"))
                .addFilterBefore(mcpTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/home", true)
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .permitAll()
                );
        return http.build();
    }

    /**
     * Неаутентифицированный запрос: для {@code /api/**} и {@code /mcp/**} —
     * только 401 без тела, для остальных — обычный редирект формы логина.
     *
     * <p>Точка входа задана явно, а не через {@code defaultAuthenticationEntryPointFor}:
     * порядок матчеров в {@code DelegatingAuthenticationEntryPoint} зависит от
     * момента регистрации form-login, и единственная запись в карте молча
     * начинает применяться ко всем путям.</p>
     */
    private static void commenceEntryPoint(HttpServletRequest request,
                                           HttpServletResponse response,
                                           AuthenticationException exception)
            throws IOException, ServletException {
        String uri = request.getRequestURI();
        if (uri != null && (uri.startsWith("/api/") || uri.equals("/mcp") || uri.startsWith("/mcp/"))) {
            new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED).commence(request, response, exception);
            return;
        }
        LOGIN_ENTRY_POINT.commence(request, response, exception);
    }

    /**
     * Фильтр уже включён в SecurityFilterChain. Отключаем автоматическую
     * регистрацию Filter-бина в контейнере, чтобы он не срабатывал дважды.
     */
    @Bean
    public FilterRegistrationBean<McpTokenAuthenticationFilter> mcpTokenFilterRegistration(
            McpTokenAuthenticationFilter filter) {
        FilterRegistrationBean<McpTokenAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
