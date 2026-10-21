package bf.restaurant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import static bf.restaurant.ApiErrors.require;

@RestController
@RequestMapping("/api/conversations")
class ConversationController {
    private final JdbcTemplate db;private final MessageService messages;
    ConversationController(JdbcTemplate db,MessageService messages) { this.db=db;this.messages=messages; }
    @GetMapping Object list(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String category,@RequestParam(defaultValue="0") int page) {
        return db.queryForList("select v.*,c.name,c.phone,c.category,(select m.body from message m where m.conversation_id=v.id order by m.created_at desc limit 1) last_message from conversation v join customer c on c.id=v.customer_id where (lower(c.name) like ? or c.phone like ?) and (?='' or v.status=?) and (?='' or c.category=?) order by v.updated_at desc limit 30 offset ?","%"+q.toLowerCase(Locale.ROOT)+"%","%"+q+"%",status,status,category,category,Math.max(0,page)*30);
    }
    record Open(@NotNull UUID customerId) {}
    @PostMapping Object open(@Valid @RequestBody Open body) {
        require(!db.queryForList("select id from customer where id=?",body.customerId()).isEmpty(),404,"Client introuvable");
        db.update("insert into conversation(id,customer_id) values(?,?) on conflict(customer_id) do nothing",UUID.randomUUID(),body.customerId());return db.queryForMap("select * from conversation where customer_id=?",body.customerId());
    }
    @GetMapping("/{id}/messages") Object history(@PathVariable UUID id,@RequestParam(defaultValue="0") int page) { return db.queryForList("select * from message where conversation_id=? order by created_at desc,id limit 50 offset ?",id,Math.max(0,page)*50); }
    @PostMapping("/{id}/read") void read(@PathVariable UUID id) { db.update("update conversation set unread=0 where id=?",id); }
    record Send(@NotNull UUID requestKey,@NotBlank @Size(max=4096) String body) {}
    @PostMapping("/{id}/messages") Object send(@PathVariable UUID id,@Valid @RequestBody Send body) { return messages.send(id,body.requestKey(),body.body()); }
    record State(@Pattern(regexp="OPEN|CLOSED") @NotNull String status) {}
    @PutMapping("/{id}") void state(@PathVariable UUID id,@Valid @RequestBody State state) { require(db.update("update conversation set status=? where id=?",state.status(),id)==1,404,"Discussion introuvable"); }
}
