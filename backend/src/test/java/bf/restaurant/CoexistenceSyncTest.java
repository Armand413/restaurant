package bf.restaurant;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
class CoexistenceSyncTest {
    @Autowired JdbcTemplate db;@Autowired CoexistenceSync sync;@Autowired org.springframework.transaction.support.TransactionTemplate tx;
    String queue(String field,String value) {
        String key=SecurityConfig.hash(UUID.randomUUID().toString());
        db.update("insert into webhook_event(event_key,field,payload,state) values(?,?,?,'QUEUED')",key,field,"{\"value\":"+value+"}");return key;
    }
    void process(String key) { tx.executeWithoutResult(s->sync.process(key)); }
    @Test void historyIsIdempotentDoesNotOpenServiceWindowAndUsesRealProgress() {
        String key=queue("history","""
          {"metadata":{"display_phone_number":"22670000000"},"history":[{"metadata":{"phase":1,"chunk_order":4,"progress":55},"threads":[{"id":"22670666666","messages":[
          {"from":"22670666666","id":"wamid.history.test.in","timestamp":"1739230955","type":"text","text":{"body":"Ancien message"}},
          {"from":"22670000000","id":"wamid.history.test.out","timestamp":"1739230970","type":"text","text":{"body":"Réponse historique"},"history_context":{"status":"READ"}}
          ]}]}]}
          """);
        process(key);process(key);
        assertEquals(1,db.queryForObject("select count(*) from message where whatsapp_id='wamid.history.test.in'",Integer.class));
        assertEquals("READ",db.queryForObject("select status from message where whatsapp_id='wamid.history.test.out'",String.class));
        var conversation=db.queryForMap("select v.* from conversation v join customer c on c.id=v.customer_id where c.phone='+22670666666'");
        assertNull(conversation.get("last_inbound_at"));assertEquals(0,((Number)conversation.get("unread")).intValue());
        assertEquals(55,db.queryForObject("select progress from whatsapp_sync_progress where event_key=?",Integer.class,key));
    }
    @Test void echoAndContactImportDoNotGrantMarketingConsent() {
        String contact=queue("smb_app_state_sync","{\"state_sync\":[{\"type\":\"contact\",\"action\":\"add\",\"contact\":{\"phone_number\":\"22670555555\",\"full_name\":\"Contact de test isolé\"}}]}");
        process(contact);process(contact);
        String echo=queue("smb_message_echoes","{\"message_echoes\":[{\"from\":\"22670000000\",\"to\":\"22670555555\",\"id\":\"wamid.echo.test\",\"timestamp\":\"1739230955\",\"type\":\"text\",\"text\":{\"body\":\"Écho réel de test\"}}]}");
        process(echo);process(echo);
        assertEquals("WHATSAPP_APP",db.queryForObject("select source from message where whatsapp_id='wamid.echo.test'",String.class));
        assertEquals(0,db.queryForObject("select count(*) from consent_record r join customer c on c.id=r.customer_id where c.phone='+22670555555'",Integer.class));
        String removal=queue("smb_app_state_sync","{\"state_sync\":[{\"type\":\"contact\",\"action\":\"remove\",\"contact\":{\"phone_number\":\"22670555555\"}}]}");process(removal);
        assertTrue(db.queryForObject("select removed from whatsapp_import where kind='CONTACT' and external_id='22670555555'",Boolean.class));
        assertEquals(1,db.queryForObject("select count(*) from message where whatsapp_id='wamid.echo.test'",Integer.class));
    }
    @Test void refusalHasNoInventedProgress() {
        String key=queue("history","{\"history\":[{\"errors\":[{\"code\":2593109}]}]}");process(key);
        assertEquals(0,db.queryForObject("select count(*) from whatsapp_sync_progress where event_key=?",Integer.class,key));
        assertEquals("PROCESSED",db.queryForObject("select state from webhook_event where event_key=?",String.class,key));
    }
    @Test void mediaFollowupCanArriveBeforeItsHistoryMessage() {
        String media=queue("history","{\"messages\":[{\"id\":\"wamid.history.media.test\",\"type\":\"image\",\"image\":{\"id\":\"isolated-media-id\"}}]}");process(media);
        String history=queue("history","{\"metadata\":{\"display_phone_number\":\"22670000000\"},\"history\":[{\"metadata\":{\"phase\":0,\"chunk_order\":1,\"progress\":30},\"threads\":[{\"id\":\"22670444444\",\"messages\":[{\"from\":\"22670444444\",\"id\":\"wamid.history.media.test\",\"timestamp\":\"1739230955\",\"type\":\"media_placeholder\"}]}]}]}");process(history);process(media);
        var imported=db.queryForMap("select type,media_id from message where whatsapp_id='wamid.history.media.test'");
        assertEquals("image",imported.get("type"));assertEquals("isolated-media-id",imported.get("media_id"));
    }
}
