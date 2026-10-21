package bf.restaurant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static bf.restaurant.ApiErrors.require;

@RestController
@RequestMapping("/api/orders")
class OrderController {
    private final JdbcTemplate db;
    OrderController(JdbcTemplate db) { this.db=db; }
    record Line(@NotNull UUID dishId,@Min(1) @Max(100) int quantity) {}
    record Input(@NotNull UUID requestKey,@NotNull UUID customerId,@NotNull @Pattern(regexp="PICKUP|DELIVERY") String fulfillment,@NotNull @Size(max=1000) String address,@NotNull @Size(max=1000) String instructions,@NotEmpty @Size(max=100) List<@Valid Line> items) {}
    static long lineTotal(long price,int quantity) { require(price>=0 && quantity>=1 && quantity<=100,400,"Quantité ou prix invalide");return Math.multiplyExact(price,quantity); }
    static boolean transition(String from,String to) { return switch(from) { case "PENDING" -> Set.of("CONFIRMED","CANCELLED").contains(to);case "CONFIRMED" -> Set.of("PREPARING","CANCELLED").contains(to);case "PREPARING" -> Set.of("READY","CANCELLED").contains(to);case "READY" -> Set.of("DELIVERED","CANCELLED").contains(to);default -> false; }; }
    @PostMapping @Transactional Object create(@Valid @RequestBody Input input,Principal actor) {
        require(!input.fulfillment().equals("DELIVERY")||!input.address().isBlank(),400,"Adresse requise pour la livraison");
        require(!db.queryForList("select id from customer where id=? for update",input.customerId()).isEmpty(),404,"Client introuvable");
        var previous=db.queryForList("select id,total,paid,request_fingerprint from customer_order where request_key=?",input.requestKey());
        if(!previous.isEmpty()) { var order=previous.getFirst();require(SecurityConfig.hash(input.toString()).equals(order.get("request_fingerprint")),409,"Clé de commande déjà utilisée avec d’autres données");return Map.of("id",order.get("id"),"total",order.get("total"),"paid",order.get("paid")); }
        UUID id=UUID.randomUUID();long total=0; var lines=new ArrayList<Map<String,Object>>();
        var seen=new HashSet<UUID>();
        for(var item:input.items()) {
            require(seen.add(item.dishId()),400,"Regroupez les quantités du même plat");
            var rows=db.queryForList("select * from dish where id=? for update",item.dishId()); require(!rows.isEmpty(),404,"Plat introuvable");var dish=rows.getFirst();require(Boolean.TRUE.equals(dish.get("available")),409,"Plat indisponible");
            long price=((Number)dish.get("price")).longValue();total=Math.addExact(total,lineTotal(price,item.quantity()));
            lines.add(Map.of("dishId",item.dishId(),"name",dish.get("name"),"quantity",item.quantity(),"price",price));
        }
        db.update("insert into customer_order(id,customer_id,status,fulfillment,address,instructions,total,request_key,request_fingerprint) values(?,?,'PENDING',?,?,?,?,?,?)",id,input.customerId(),input.fulfillment(),input.address(),input.instructions(),total,input.requestKey(),SecurityConfig.hash(input.toString()));
        for(var l:lines) db.update("insert into order_item(id,order_id,dish_id,name,quantity,unit_price) values(?,?,?,?,?,?)",UUID.randomUUID(),id,l.get("dishId"),l.get("name"),l.get("quantity"),l.get("price"));
        db.update("insert into order_status_history(id,order_id,status,actor) values(?,?,'PENDING',?)",UUID.randomUUID(),id,UUID.fromString(actor.getName()));return Map.of("id",id,"total",total,"paid",false);
    }
    @GetMapping Object list(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="0") int page) {
        return db.queryForList("select o.*,c.name customer_name from customer_order o join customer c on c.id=o.customer_id where (?='' or o.status=?) and (lower(c.name) like ? or cast(o.id as varchar) like ?) order by o.created_at desc limit 30 offset ?",status,status,"%"+q.toLowerCase(Locale.ROOT)+"%","%"+q+"%",Math.max(0,page)*30);
    }
    @GetMapping("/{id}") Object detail(@PathVariable UUID id) { return Map.of("order",db.queryForMap("select * from customer_order where id=?",id),"items",db.queryForList("select * from order_item where order_id=?",id),"history",db.queryForList("select * from order_status_history where order_id=? order by changed_at",id)); }
    record Status(@NotBlank String status) {}
    @PostMapping("/{id}/status") @Transactional void status(@PathVariable UUID id,@Valid @RequestBody Status body,Principal actor) {
        var rows=db.queryForList("select status from customer_order where id=? for update",id);require(!rows.isEmpty(),404,"Commande introuvable");require(transition((String)rows.getFirst().get("status"),body.status()),409,"Transition de statut interdite");
        db.update("update customer_order set status=? where id=?",body.status(),id);db.update("insert into order_status_history(id,order_id,status,actor) values(?,?,?,?)",UUID.randomUUID(),id,body.status(),UUID.fromString(actor.getName()));
    }
}
