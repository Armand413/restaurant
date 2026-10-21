package bf.restaurant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static bf.restaurant.ApiErrors.require;

@RestController
@RequestMapping("/api")
class CatalogController {
    private final DishRepository dishes; private final JdbcTemplate db;
    CatalogController(DishRepository dishes,JdbcTemplate db) { this.dishes=dishes;this.db=db; }
    @GetMapping("/dishes") Object dishes(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="") String q) { var result=dishes.findByNameContainingIgnoreCase(q,PageRequest.of(Math.max(0,page),30,Sort.by("name")));return Map.of("content",result.getContent(),"total",result.getTotalElements(),"page",page); }
    @PostMapping("/dishes") @PreAuthorize("hasRole('ADMIN')") Object create(@Valid @RequestBody Dish dish) { dish.id=UUID.randomUUID();dish.version=0;return dishes.save(dish); }
    @PutMapping("/dishes/{id}") @PreAuthorize("hasRole('ADMIN')") @Transactional Object edit(@PathVariable UUID id,@Valid @RequestBody Dish incoming) {
        var d=dishes.findById(id).orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        require(d.version==incoming.version,409,"Le plat a été modifié par un autre utilisateur");
        d.name=incoming.name;d.description=incoming.description;d.category=incoming.category;d.price=incoming.price;d.available=incoming.available;d.imageUrl=incoming.imageUrl;return dishes.save(d);
    }
    @DeleteMapping("/dishes/{id}") @PreAuthorize("hasRole('ADMIN')") void delete(@PathVariable UUID id) { require(dishes.existsById(id),404,"Plat introuvable");dishes.deleteById(id); }
    record MenuInput(@NotBlank @Size(max=160) String title,@NotNull LocalDate date,@NotEmpty @Size(max=100) List<UUID> dishIds) {}
    @PostMapping("/menus") @PreAuthorize("hasRole('ADMIN')") @Transactional Object menu(@Valid @RequestBody MenuInput input) {
        UUID id=UUID.randomUUID(); db.update("insert into daily_menu(id,menu_date,title) values(?,?,?)",id,input.date(),input.title());
        for(UUID dishId:new HashSet<>(input.dishIds())) { var d=dishes.findById(dishId).orElseThrow(); require(d.available,409,"Un plat est indisponible");db.update("insert into menu_item(menu_id,dish_id,name,price) values(?,?,?,?)",id,d.id,d.name,d.price); }
        return Map.of("id",id);
    }
    @GetMapping("/menus") Object menus(@RequestParam(defaultValue="0") int page) { return db.queryForList("select * from daily_menu order by created_at desc limit 30 offset ?",Math.max(0,page)*30); }
    @GetMapping("/menus/{id}") Object menu(@PathVariable UUID id) { return Map.of("menu",db.queryForMap("select * from daily_menu where id=?",id),"items",db.queryForList("select * from menu_item where menu_id=?",id)); }
    @GetMapping("/settings") Object settings() { return db.queryForMap("select * from restaurant_settings where id=1"); }
    record Settings(@NotBlank @Size(max=160) String name) {}
    @PutMapping("/settings") @PreAuthorize("hasRole('ADMIN')") void settings(@Valid @RequestBody Settings body) { db.update("update restaurant_settings set name=? where id=1",body.name()); }
}
