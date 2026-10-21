package bf.restaurant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static bf.restaurant.ApiErrors.require;

@RestController
@RequestMapping("/api/customers")
class CustomerController {
    private final JdbcTemplate db;
    CustomerController(JdbcTemplate db) { this.db=db; }
    record CustomerInput(@NotBlank @Size(max=160) String name,@NotBlank String phone,@NotNull @Size(max=80) String category,@NotNull @Size(max=500) String tags) {}
    static String phone(String value) { String p=value.replaceAll("[\\s().-]",""); if(p.startsWith("00"))p="+"+p.substring(2);require(p.matches("\\+[1-9][0-9]{7,14}"),400,"Numéro international requis, par exemple +226…");return p; }
    @GetMapping Object list(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="0") int page) {
        return db.queryForList("select * from customer where lower(name) like ? or phone like ? order by name limit 30 offset ?","%"+q.toLowerCase(Locale.ROOT)+"%","%"+q+"%",Math.max(0,page)*30);
    }
    @PostMapping Object create(@Valid @RequestBody CustomerInput input) { UUID id=UUID.randomUUID();db.update("insert into customer(id,name,phone,category,tags) values(?,?,?,?,?)",id,input.name(),phone(input.phone()),input.category(),input.tags());return Map.of("id",id); }
    @PutMapping("/{id}") void edit(@PathVariable UUID id,@Valid @RequestBody CustomerInput input) { require(db.update("update customer set name=?,phone=?,category=?,tags=? where id=?",input.name(),phone(input.phone()),input.category(),input.tags(),id)==1,404,"Client introuvable"); }
    record Consent(@NotNull Boolean granted,@NotBlank @Size(max=200) String source) {}
    @PostMapping("/{id}/consent") @Transactional void consent(@PathVariable UUID id,@Valid @RequestBody Consent input) {
        require(!db.queryForList("select id from customer where id=? for update",id).isEmpty(),404,"Client introuvable");
        db.update("insert into consent_record(id,customer_id,granted,scope,source) values(?,?,?,'MARKETING',?)",UUID.randomUUID(),id,input.granted(),input.source());
        db.update("update customer set unsubscribed=? where id=?",!input.granted(),id);
    }
    @GetMapping("/{id}") Object details(@PathVariable UUID id) { return Map.of("customer",db.queryForMap("select * from customer where id=?",id),"consents",db.queryForList("select * from consent_record where customer_id=? order by recorded_at desc",id),"orders",db.queryForList("select * from customer_order where customer_id=? order by created_at desc limit 100",id),"conversations",db.queryForList("select * from conversation where customer_id=?",id)); }
}
