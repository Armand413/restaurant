package bf.restaurant;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;
import static bf.restaurant.ApiErrors.require;

@Configuration
@EnableMethodSecurity
class SecurityConfig {
    @Bean BCryptPasswordEncoder encoder() { return new BCryptPasswordEncoder(12); }
    @Bean SecurityFilterChain security(HttpSecurity http, JdbcTemplate db, @Value("${app.cors-origin}") String origin) throws Exception {
        var cors = new CorsConfiguration(); cors.setAllowedOrigins(List.of(origin)); cors.setAllowedMethods(List.of("GET","POST","PUT","DELETE","OPTIONS")); cors.setAllowedHeaders(List.of("Authorization","Content-Type","Idempotency-Key"));
        var source = new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/**", cors);
        return http.csrf(c -> c.disable()).cors(c -> c.configurationSource(source))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a.requestMatchers("/api/auth/login","/webhooks/whatsapp","/actuator/health","/connect/whatsapp","/connect/whatsapp.js","/connect/whatsapp.css","/connect/whatsapp/**").permitAll().anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint((req,res,ex) -> res.sendError(401)).accessDeniedHandler((req,res,ex) -> res.sendError(403)))
            .addFilterBefore(new SessionFilter(db), UsernamePasswordAuthenticationFilter.class).build();
    }
    @Bean ApplicationRunner bootstrap(JdbcTemplate db, BCryptPasswordEncoder encoder, @Value("${app.bootstrap-email}") String email, @Value("${app.bootstrap-password}") String password) {
        return args -> {
            if (!email.isBlank() && db.queryForObject("select count(*) from app_user",Long.class)==0) {
                require(password.length()>=14 && password.getBytes(StandardCharsets.UTF_8).length<=72,400,"Mot de passe initial de 14 caractères minimum et 72 octets maximum requis");
                db.update("insert into app_user(id,email,password_hash,role) values(?,?,?,'ADMIN')",UUID.randomUUID(),email.toLowerCase(Locale.ROOT),encoder.encode(password));
            }
            // A crash after claiming an external send is ambiguous: never resend automatically.
            db.update("update message set status='UNKNOWN',error_code='PROCESS_INTERRUPTED' where status='PENDING' and direction='OUT'");
            db.update("update campaign_recipient set status='UNKNOWN',error_code='PROCESS_INTERRUPTED' where status='SENDING'");
        };
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static class SessionFilter extends OncePerRequestFilter {
        private final JdbcTemplate db;
        private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
        record Window(long minute, int count) {}
        SessionFilter(JdbcTemplate db) { this.db=db; }
        @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
            if (req.getRequestURI().startsWith("/api/") || req.getRequestURI().startsWith("/connect/")) {
                long minute=Instant.now().getEpochSecond()/60;
                if(windows.size()>10000) windows.entrySet().removeIf(e -> e.getValue().minute()<minute);
                String bucket=req.getRemoteAddr()+ (req.getRequestURI().equals("/api/auth/login")?":login":req.getRequestURI().startsWith("/connect/")?":onboarding":":api");
                Window w=windows.compute(bucket,(k,v)->v==null||v.minute()!=minute?new Window(minute,1):new Window(minute,v.count()+1));
                if(w.count()>(bucket.endsWith(":login")?10:bucket.endsWith(":onboarding")?30:240)) { res.setHeader("Retry-After","60"); res.sendError(429); return; }
            }
            String header=req.getHeader("Authorization");
            if(header!=null && header.startsWith("Bearer ") && header.length()<200) {
                var users=db.queryForList("select u.id,u.role from app_session s join app_user u on u.id=s.user_id where s.token_hash=? and s.expires_at>CURRENT_TIMESTAMP and u.enabled=true", hash(header.substring(7)));
                if(!users.isEmpty()) { var u=users.getFirst(); SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u.get("id").toString(),null,List.of(new SimpleGrantedAuthority("ROLE_"+u.get("role"))))); }
            }
            chain.doFilter(req,res);
        }
    }
}

@RestController
@RequestMapping("/api/auth")
class AuthController {
    private final JdbcTemplate db; private final BCryptPasswordEncoder encoder;
    private final String dummy;
    AuthController(JdbcTemplate db,BCryptPasswordEncoder encoder) { this.db=db;this.encoder=encoder;dummy=encoder.encode(UUID.randomUUID().toString()); }
    record Login(@Email @NotBlank @Size(max=200) String email,@NotBlank @Size(max=72) String password) {}
    @PostMapping("/login") Object login(@Valid @RequestBody Login body) {
        var rows=db.queryForList("select * from app_user where email=? and enabled=true",body.email().toLowerCase(Locale.ROOT));
        boolean valid=encoder.matches(body.password(),rows.isEmpty()?dummy:(String)rows.getFirst().get("password_hash"));
        require(valid && !rows.isEmpty(),401,"Identifiants invalides");
        var user=rows.getFirst(); byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.update("insert into app_session(token_hash,user_id,expires_at) values(?,?,?)",SecurityConfig.hash(token),user.get("id"),OffsetDateTime.now().plusHours(12));
        return Map.of("token",token,"role",user.get("role"),"expiresIn",43200);
    }
    @GetMapping("/me") Object me(java.security.Principal principal) { return db.queryForMap("select id,email,role from app_user where id=?",UUID.fromString(principal.getName())); }
    @PostMapping("/logout") void logout(@RequestHeader("Authorization") String auth) { db.update("delete from app_session where token_hash=?",SecurityConfig.hash(auth.substring(7))); }
    record NewUser(@Email @NotBlank String email,@Size(min=14,max=72) String password,@Pattern(regexp="ADMIN|EMPLOYEE") String role) {}
    @PostMapping("/users") @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    Object create(@Valid @RequestBody NewUser body) {
        require(body.password()!=null && body.password().getBytes(StandardCharsets.UTF_8).length<=72,400,"Mot de passe limité à 72 octets UTF-8");
        UUID id=UUID.randomUUID(); db.update("insert into app_user(id,email,password_hash,role) values(?,?,?,?)",id,body.email().toLowerCase(Locale.ROOT),encoder.encode(body.password()),body.role()); return Map.of("id",id);
    }
}
