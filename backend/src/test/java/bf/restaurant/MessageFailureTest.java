package bf.restaurant;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;

class MessageFailureTest {
    @Test void ambiguousMetaFailureIsNeverReportedSentOrRetried() {
        var db=mock(JdbcTemplate.class);var meta=mock(MetaClient.class);UUID id=UUID.randomUUID();
        when(meta.send(anyString(),anyString(),any())).thenThrow(new MetaClient.MetaFailure("META_UNCONFIRMED",true));
        new MessageService(db,meta,null).dispatch(id,"+22670000000","text",Map.of("body","test"));
        verify(db).update("update message set status=?,error_code=? where id=?","UNKNOWN","META_UNCONFIRMED",id);
        verify(meta,times(1)).send(anyString(),anyString(),any());
    }
    @Test void rejectedMessageIsFailedAndNotRetried() {
        var db=mock(JdbcTemplate.class);var meta=mock(MetaClient.class);UUID id=UUID.randomUUID();
        when(meta.send(anyString(),anyString(),any())).thenThrow(new MetaClient.MetaFailure("META_HTTP_400",false));
        new MessageService(db,meta,null).dispatch(id,"+22670000000","text",Map.of("body","test"));
        verify(db).update("update message set status=?,error_code=? where id=?","FAILED","META_HTTP_400",id);
        verify(meta,times(1)).send(anyString(),anyString(),any());
    }
}
