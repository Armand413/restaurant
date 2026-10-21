package bf.restaurant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class CampaignPreviewTest {
    @Test void previewContainsRealTemplateTextAroundMenu() throws Exception {
        var meta=mock(MetaClient.class);
        when(meta.request(eq("GET"),anyString(),isNull())).thenReturn(new ObjectMapper().readTree("""
        {"data":[{"name":"menu_jour","language":"fr","status":"APPROVED","category":"MARKETING","components":[{"type":"HEADER","format":"TEXT","text":"Restaurant"},{"type":"BODY","text":"Bonjour, voici le menu : {{1}}"},{"type":"FOOTER","text":"À bientôt"}]}]}
        """));
        assertEquals("Restaurant\nBonjour, voici le menu : Riz : 2500 FCFA\nÀ bientôt",new CampaignService(null,meta,null,null).approved("menu_jour","fr","Riz : 2500 FCFA"));
    }
    @Test void unapprovedTemplateCannotBeSent() throws Exception {
        var meta=mock(MetaClient.class);when(meta.request(eq("GET"),anyString(),isNull())).thenReturn(new ObjectMapper().readTree("{\"data\":[]}"));
        assertThrows(RuntimeException.class,()->new CampaignService(null,meta,null,null).approved("menu_jour","fr","Menu"));
    }
}
