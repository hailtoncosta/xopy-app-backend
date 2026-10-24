package br.com.xopy;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.CONFLICT;
final class ServiceCaseGuard {
 private ServiceCaseGuard(){}
 static String state(JdbcTemplate db,UUID conversation,boolean lock){var rows=db.queryForList("SELECT service_case_status FROM conversations WHERE id=?"+(lock?" FOR UPDATE":""),conversation);return rows.isEmpty()?"active":Objects.toString(rows.get(0).get("service_case_status"),"active");}
 static void requireActive(JdbcTemplate db,UUID conversation){String state=state(db,conversation,true);if(!state.equals("active"))throw new ResponseStatusException(CONFLICT,state.equals("disputed")?"Contratação em disputa. Aguarde a resolução pelo Xopy.":"Contratação cancelada");}
 static void checkout(JdbcTemplate db,UUID checkout,UUID buyer){var rows=db.queryForList("select conversation_id from service_contracts where checkout_id=? and customer_id=?",checkout,buyer);if(!rows.isEmpty()&&rows.get(0).get("conversation_id")!=null)requireActive(db,UUID.fromString(rows.get(0).get("conversation_id").toString()));}
}
