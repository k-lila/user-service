package authorizationserver.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class ClientIpResolverTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void deveRetornarUnknown_quandoForaDeRequest() {
        RequestContextHolder.resetRequestAttributes();

        assertEquals("unknown", ClientIpResolver.currentIp("CF-Connecting-IP"));
    }

    @Test
    void devePreferirHeaderConfiavel_quandoPresente() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("CF-Connecting-IP", "203.0.113.5");
        request.setRemoteAddr("10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertEquals("203.0.113.5", ClientIpResolver.currentIp("CF-Connecting-IP"));
    }

    @Test
    void deveCairNoRemoteAddr_quandoHeaderConfiavelAusente() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertEquals("203.0.113.7", ClientIpResolver.currentIp("CF-Connecting-IP"));
    }

    @Test
    void deveRetornarRemoteAddr_quandoHeaderConfiavelDesabilitado() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("CF-Connecting-IP", "203.0.113.5");
        request.setRemoteAddr("203.0.113.7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertEquals("203.0.113.7", ClientIpResolver.currentIp(""));
    }

    @Test
    void deveRetornarUnknown_quandoRemoteAddrNulo() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(null);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertEquals("unknown", ClientIpResolver.currentIp("CF-Connecting-IP"));
    }
}
