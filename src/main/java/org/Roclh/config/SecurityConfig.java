package org.Roclh.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final McpTokenAuthenticationFilter mcpTokenAuthenticationFilter;

    public SecurityConfig(McpTokenAuthenticationFilter mcpTokenAuthenticationFilter) {
        this.mcpTokenAuthenticationFilter = mcpTokenAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/css/**", "/js/**", "/api/health").permitAll()
                        .requestMatchers("/sub/**").permitAll()
                        .requestMatchers("/invite/**").permitAll()
                        .requestMatchers("/api/nodes/**").permitAll()
                        .requestMatchers("/mcp", "/mcp/").hasRole("ADMIN")
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/ws/**").authenticated()
                        .anyRequest().authenticated()
                )
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/sub/**", "/mcp", "/mcp/"))
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
