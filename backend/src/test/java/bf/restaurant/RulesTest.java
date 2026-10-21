package bf.restaurant;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RulesTest {
    @Test void totalsUseServerIntegers() { assertEquals(7500,OrderController.lineTotal(2500,3));assertThrows(RuntimeException.class,()->OrderController.lineTotal(2500,-1));assertThrows(ArithmeticException.class,()->OrderController.lineTotal(Long.MAX_VALUE,2)); }
    @Test void terminalOrdersCannotBeReopened() { assertTrue(OrderController.transition("PENDING","CONFIRMED"));assertTrue(OrderController.transition("PREPARING","READY"));assertFalse(OrderController.transition("PENDING","DELIVERED"));assertFalse(OrderController.transition("CANCELLED","CONFIRMED"));assertFalse(OrderController.transition("DELIVERED","PENDING")); }
    @Test void phonesRequireInternationalFormat() { assertEquals("+22670123456",CustomerController.phone("00 226 70 12 34 56"));assertEquals("+22670123456",CustomerController.phone("+226 70-12-34-56"));assertThrows(RuntimeException.class,()->CustomerController.phone("70123456")); }
    @Test void signaturesRejectTamperingAndMissingSecrets() throws Exception {
        byte[] body="{\"object\":\"whatsapp_business_account\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec("test-secret".getBytes(),"HmacSHA256"));String signature="sha256="+java.util.HexFormat.of().formatHex(mac.doFinal(body));
        assertTrue(WebhookController.validSignature(body,signature,"test-secret"));assertFalse(WebhookController.validSignature("tampered".getBytes(),signature,"test-secret"));assertFalse(WebhookController.validSignature(body,null,"test-secret"));assertFalse(WebhookController.validSignature(body,signature,""));
    }
}
