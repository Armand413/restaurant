package bf.restaurant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import java.security.Principal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static bf.restaurant.ApiErrors.require;

@Service
class CampaignService {
    private volatile boolean ready=false;
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void ready() { ready=true; }
    final JdbcTemplate db;final MetaClient meta;final MessageService messages;final TransactionTemplate tx;
    CampaignService(JdbcTemplate db,MetaClient meta,MessageService messages,TransactionTemplate tx) { this.db=db;this.meta=meta;this.messages=messages;this.tx=tx; }
    boolean eligible(UUID customer) {
        var c=db.queryForList("select unsubscribed from customer where id=?",customer);if(c.isEmpty()||Boolean.TRUE.equals(c.getFirst().get("unsubscribed")))return false;
        var consent=db.queryForList("select granted from consent_record where customer_id=? and scope='MARKETING' order by recorded_at desc limit 1",customer);return !consent.isEmpty()&&Boolean.TRUE.equals(consent.getFirst().get("granted"));
    }
    String approved(String name,String language,String parameter) {
        require(name.matches("[a-z0-9_]{1,512}")&&language.matches("[a-zA-Z_]{2,20}"),400,"Modèle ou langue invalide");
        var result=meta.request("GET",meta.businessId()+"/message_templates?name="+name+"&fields=name,status,category,language,components",null);
        for(var t:result.path("data")) {
            if(!t.path("name").asText().equals(name)||!t.path("language").asText().equals(language)||!t.path("status").asText().equals("APPROVED")||!t.path("category").asText().equals("MARKETING"))continue;
            boolean body=false,supported=true;String header="",renderedBody="",footer="";
            for(var component:t.path("components")) {
                String type=component.path("type").asText(),text=component.path("text").asText();
                if(type.equals("BODY")) { body=text.contains("{{1}}")&&!text.replace("{{1}}","").contains("{{");renderedBody=text.replace("{{1}}",parameter); }
                else if(type.equals("FOOTER")) { footer=text; }
                else if(type.equals("HEADER")&&component.path("format").asText().equals("TEXT")&&!text.contains("{{")) { header=text; }
                else supported=false;
            }
            require(body&&supported,409,"Modèle requis : corps avec un paramètre {{1}}, sans bouton ni en-tête dynamique");return java.util.stream.Stream.of(header,renderedBody,footer).filter(s->!s.isBlank()).collect(java.util.stream.Collectors.joining("\n"));
        }
        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Modèle marketing approuvé introuvable pour cette langue");
    }
    @Scheduled(fixedDelay=2000)
    void deliverOne() {
        if(!ready || !meta.configured())return;
        var job=tx.execute(s -> {
            var rows=db.queryForList("select r.*,c.template_name,c.language,c.preview,c.parameter_value from campaign_recipient r join campaign c on c.id=r.campaign_id where c.status='RUNNING' and r.status='PENDING' order by c.created_at limit 1 for update of r skip locked");
            if(rows.isEmpty())return null;var r=rows.getFirst();UUID customer=(UUID)r.get("customer_id");
            db.queryForList("select id from customer where id=? for update",customer);
            if(!eligible(customer)) { db.update("update campaign_recipient set status='EXCLUDED',error_code='CONSENT_REVOKED' where id=?",r.get("id"));return null; }
            db.update("insert into conversation(id,customer_id) values(?,?) on conflict(customer_id) do nothing",UUID.randomUUID(),customer);
            var conversation=db.queryForMap("select id from conversation where customer_id=?",customer);UUID message=UUID.randomUUID();
            db.update("insert into message(id,conversation_id,request_key,direction,type,body,status) values(?,?,?,'OUT','template',?,'PENDING')",message,conversation.get("id"),r.get("id"),r.get("preview"));
            db.update("update conversation set updated_at=CURRENT_TIMESTAMP where id=?",conversation.get("id"));
            db.update("update campaign_recipient set status='SENDING',message_id=? where id=?",message,r.get("id"));r.put("message",message);r.put("phone",db.queryForMap("select phone from customer where id=?",customer).get("phone"));return r;
        });
        if(job!=null) {
            Object content=Map.of("name",job.get("template_name"),"language",Map.of("code",job.get("language")),"components",List.of(Map.of("type","body","parameters",List.of(Map.of("type","text","text",job.get("parameter_value"))))));
            messages.dispatch((UUID)job.get("message"),(String)job.get("phone"),"template",content);
            var message=db.queryForMap("select status,error_code from message where id=?",job.get("message"));db.update("update campaign_recipient set status=?,error_code=? where id=?",message.get("status"),message.get("error_code"),job.get("id"));
        }
        db.update("update campaign set status='COMPLETED' where status='RUNNING' and not exists(select 1 from campaign_recipient r where r.campaign_id=campaign.id and r.status in ('PENDING','SENDING'))");
    }
}

@RestController
@RequestMapping("/api/campaigns")
@PreAuthorize("hasRole('ADMIN')")
class CampaignController {
    private final CampaignService service;private final JdbcTemplate db;
    CampaignController(CampaignService service,JdbcTemplate db) { this.service=service;this.db=db; }
    record Draft(@NotNull UUID menuId,@NotBlank String templateName,@NotBlank String language,@NotEmpty @Size(max=500) List<UUID> customerIds) {}
    @PostMapping @Transactional Object draft(@Valid @RequestBody Draft body) {
        var menus=db.queryForList("select title from daily_menu where id=?",body.menuId());require(!menus.isEmpty(),404,"Menu introuvable");
        StringBuilder preview=new StringBuilder(menus.getFirst().get("title").toString());
        for(var item:db.queryForList("select name,price from menu_item where menu_id=? order by name",body.menuId()))preview.append("\n").append(item.get("name")).append(" : ").append(item.get("price")).append(" FCFA");
        preview.append("\nRépondez STOP pour vous désabonner.");require(preview.length()<=1000,400,"Menu trop long pour ce modèle");
        String rendered=service.approved(body.templateName(),body.language(),preview.toString());
        UUID id=UUID.randomUUID();db.update("insert into campaign(id,menu_id,template_name,language,preview,parameter_value) values(?,?,?,?,?,?)",id,body.menuId(),body.templateName(),body.language(),rendered,preview.toString());
        int count=0;for(UUID customer:new HashSet<>(body.customerIds()))if(service.eligible(customer)) { db.update("insert into campaign_recipient(id,campaign_id,customer_id) values(?,?,?)",UUID.randomUUID(),id,customer);count++; }
        require(count>0,409,"Aucun destinataire avec consentement marketing actif");return Map.of("id",id,"recipientCount",count,"preview",rendered,"status","DRAFT");
    }
    record Confirmation(@AssertTrue boolean confirmed) {}
    @PostMapping("/{id}/confirm") @Transactional void confirm(@PathVariable UUID id,@Valid @RequestBody Confirmation body,Principal actor) {
        var rows=db.queryForList("select * from campaign where id=? for update",id);require(!rows.isEmpty(),404,"Campagne introuvable");var c=rows.getFirst();require(c.get("status").equals("DRAFT"),409,"Campagne déjà lancée");require(service.approved((String)c.get("template_name"),(String)c.get("language"),(String)c.get("parameter_value")).equals(c.get("preview")),409,"Le modèle a changé. Préparez et prévisualisez un nouveau brouillon.");
        db.update("update campaign set status='RUNNING' where id=?",id);db.update("insert into audit_log(id,actor,action,target) values(?,?,'CAMPAIGN_CONFIRMED',?)",UUID.randomUUID(),UUID.fromString(actor.getName()),id.toString());
    }
    @GetMapping Object list(@RequestParam(defaultValue="0") int page) { return db.queryForList("select * from campaign order by created_at desc limit 30 offset ?",Math.max(0,page)*30); }
    @GetMapping("/{id}") Object detail(@PathVariable UUID id) { return Map.of("campaign",db.queryForMap("select * from campaign where id=?",id),"recipients",db.queryForList("select r.*,c.name,m.status delivery_status from campaign_recipient r join customer c on c.id=r.customer_id left join message m on m.id=r.message_id where campaign_id=?",id)); }
}
