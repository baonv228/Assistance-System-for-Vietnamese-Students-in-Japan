package com.sep490.vsa.feature.faq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sep490.vsa.common.security.AppUserPrincipal;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import static com.sep490.vsa.feature.faq.FaqDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
        "spring.datasource.url=jdbc:mysql://localhost:3306/${faq.test.database:asvsj_faq_test_20261103}?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.places.osm.enabled=false", "app.moderation.retry-initial-delay-ms=3600000", "app.media.local-dir=target/faq-test-uploads"
})
@AutoConfigureMockMvc @ActiveProfiles("local") @Transactional
class FaqFlowTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FaqService service;
    @Autowired ObjectMapper json;

    private MockHttpSession session(String role) {
        String tag=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users(email,display_name,role,status,is_adult_confirmed) VALUES (?,?,?,'ACTIVE',TRUE)",tag+"@test.local",tag,role);
        long id=jdbc.queryForObject("SELECT id FROM users WHERE email=?",Long.class,tag+"@test.local");
        var auth=new UsernamePasswordAuthenticationToken(new AppUserPrincipal(id,tag+"@test.local",tag,role,"/moderator"),null,List.of(new SimpleGrantedAuthority("ROLE_"+role)));
        var session=new MockHttpSession();session.setAttribute("SPRING_SECURITY_CONTEXT",new SecurityContextImpl(auth));return session;
    }
    private AppUserPrincipal principal(MockHttpSession session) {return (AppUserPrincipal)((SecurityContextImpl)session.getAttribute("SPRING_SECURITY_CONTEXT")).getAuthentication().getPrincipal();}
    private MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder request,MockHttpSession session) {return request.session(session).cookie(new Cookie("XSRF-TOKEN","token")).header("X-XSRF-TOKEN","token");}
    private Write request(String question,boolean published) {return new Write(question,"Helpful answer","LIVING",published,List.of(),null,null);}
    private long[] source(long author,String title) {
        jdbc.update("INSERT INTO blogs(author_id,blog_type,title,content,status) VALUES (?,'QA',?,'Question content','PUBLISHED')",author,title);
        long id=jdbc.queryForObject("SELECT id FROM blogs WHERE title=?",Long.class,title);
        jdbc.update("INSERT INTO comments(blog_id,author_id,content,status,like_count) VALUES (?,?,'Best answer','PUBLISHED',10)",id,author);
        long comment=jdbc.queryForObject("SELECT id FROM comments WHERE blog_id=?",Long.class,id);
        jdbc.update("UPDATE blogs SET best_comment_id=? WHERE id=?",comment,id);return new long[]{id,comment};
    }

    @Test void publicSearchTopicsPaginationAndHiddenEntries() throws Exception {
        var moderator=session("MODERATOR");String key=UUID.randomUUID().toString();
        var first=service.save(null,request("Alpha "+key,true),principal(moderator));
        service.save(null,request("Beta "+key,true),principal(moderator));
        var hidden=service.save(null,request("Hidden "+key,false),principal(moderator));
        mvc.perform(get("/api/faqs").param("q",key).param("size","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.totalPages").value(2));
        mvc.perform(get("/api/faqs").param("q",key).param("topic","PROCEDURES"))
                .andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/faqs/topics").param("q",key)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[1].count").value(2));
        mvc.perform(get("/api/faqs/"+hidden.id())).andExpect(status().isNotFound());
        mvc.perform(get("/api/faqs/"+first.id())).andExpect(jsonPath("$.data.answer").value("Helpful answer"));
        mvc.perform(get("/faq")).andExpect(status().isOk()).andExpect(forwardedUrl("/pages/faq.html"));
        mvc.perform(get("/moderator/faqs").session(moderator)).andExpect(status().isOk());
    }

    /** A word no other entry holds: letters and digits only, so the search and the matcher see it as one word. */
    private static String word() {return "w"+UUID.randomUUID().toString().replace("-","").substring(0,12);}

    @Test void relatedEntriesAreFoundByTheirWordsBestFirstAndOnlyWhenPublished() throws Exception {
        var moderator=session("MODERATOR");String a=word(),b=word(),c=word();
        var best=service.save(null,new Write("Làm sao "+a+" "+b+" "+c+"?","Trả lời","LIVING",true,List.of(),null,null),principal(moderator));
        var weaker=service.save(null,new Write("Hỏi về "+a+" "+b,"Có nói "+c,"OTHER",true,List.of(new Resource("EXTERNAL",null,"https://www.moj.go.jp/","Cục xuất nhập cảnh")),null,null),principal(moderator));
        service.save(null,new Write(a+" "+b+" "+c+" ẩn","Trả lời","LIVING",false,List.of(),null,null),principal(moderator));
        mvc.perform(get("/api/faqs/related").param("q","Mình muốn hỏi "+a.toUpperCase()+" "+b+" "+c+" thế nào ạ?"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(best.id())).andExpect(jsonPath("$.data[1].id").value(weaker.id()))
                .andExpect(jsonPath("$.data[0].topicName").value("Sinh hoạt và đời sống"))
                .andExpect(jsonPath("$.data[1].answer").value("Có nói "+c))
                .andExpect(jsonPath("$.data[1].resources[0].label").value("Cục xuất nhập cảnh"));
        // The number asked for is kept within 1–5 (default 3)
        mvc.perform(get("/api/faqs/related").param("q",a+" "+b).param("limit","1")).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/api/faqs/related").param("q",a+" "+b).param("limit","0")).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/api/faqs/related").param("q",a+" "+b).param("limit","99")).andExpect(jsonPath("$.data.length()").value(2));
        // Question words alone, nothing, or too long a text
        mvc.perform(get("/api/faqs/related").param("q","Làm sao ạ?")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/faqs/related")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/faqs/related").param("q","x".repeat(201))).andExpect(status().isBadRequest());
        // An entry hidden later is no longer offered
        mvc.perform(csrf(put("/api/moderator/faqs/"+best.id()+"/visibility"),moderator).contentType(MediaType.APPLICATION_JSON).content("{\"published\":false}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/faqs/related").param("q",a+" "+b+" "+c)).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(weaker.id()));
    }

    @Test void searchIgnoresAccentsAndCaseAndTakesWildcardsLiterally() throws Exception {
        var moderator=session("MODERATOR");String key=word();
        var entry=service.save(null,new Write("Mở tài khoản ngân hàng "+key,"Mang thẻ cư trú "+key,"PROCEDURES",true,List.of(),null,null),principal(moderator));
        mvc.perform(get("/api/faqs").param("q","TAI KHOAN NGAN HANG "+key))
                .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].id").value(entry.id()));
        mvc.perform(get("/api/faqs").param("q","the cu tru "+key.toUpperCase())).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/faqs/topics").param("q","tài khoản ngân hàng "+key))
                .andExpect(jsonPath("$.data[?(@.code=='PROCEDURES')].count").value(1))
                .andExpect(jsonPath("$.data[?(@.code=='LIVING')].count").value(0));
        mvc.perform(get("/api/moderator/faqs").session(moderator).param("q","mo tai khoan "+"ngan hang "+key))
                .andExpect(jsonPath("$.data.total").value(1));
        // % and _ are characters to find, not wildcards
        mvc.perform(get("/api/faqs").param("q","ngân_hàng "+key)).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/faqs").param("q","Mở%"+key)).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/faqs").param("q","\\"+key)).andExpect(jsonPath("$.data.total").value(0));
    }

    @Test void moderatorCreatesEditsHidesAndRepublishesWhileOtherRolesAreBlocked() throws Exception {
        var moderator=session("MODERATOR");var member=session("USER");
        mvc.perform(get("/api/moderator/faqs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/moderator/faqs").session(member)).andExpect(status().isForbidden());
        mvc.perform(csrf(post("/api/moderator/faqs"),member).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request("Q",true))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/moderator/faqs").session(moderator).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request("Q",true))))
                .andExpect(status().isForbidden());
        var response=mvc.perform(csrf(post("/api/moderator/faqs"),moderator).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request("Original",true))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        long id=json.readTree(response.getContentAsByteArray()).get("data").get("id").asLong();
        mvc.perform(csrf(put("/api/moderator/faqs/"+id),moderator).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request("Edited",true))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.question").value("Edited"));
        mvc.perform(csrf(put("/api/moderator/faqs/"+id+"/visibility"),moderator).contentType(MediaType.APPLICATION_JSON).content("{\"published\":false}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/faqs/"+id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/moderator/faqs/"+id).session(moderator)).andExpect(status().isOk());
        mvc.perform(csrf(put("/api/moderator/faqs/"+id+"/visibility"),moderator).contentType(MediaType.APPLICATION_JSON).content("{\"published\":true}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/faqs/"+id)).andExpect(status().isOk());
    }

    @Test void bestAnswersBecomeSuggestionsAndCanCreateOrEnrichFaqExactlyOnce() throws Exception {
        var moderator=session("MODERATOR");var author=session("USER");String key=UUID.randomUUID().toString();
        long[] first=source(principal(author).id(),"Question one "+key),second=source(principal(author).id(),"Question two "+key);
        mvc.perform(get("/api/moderator/faqs/suggestions").session(moderator).param("q",key))
                .andExpect(jsonPath("$.data.total").value(2));
        var create=new Write("Curated FAQ","Reviewed answer","LIVING",true,List.of(new Resource("QUESTION",first[0],null,"Câu hỏi gốc")),first[0],first[1]);
        var response=mvc.perform(csrf(post("/api/moderator/faqs"),moderator).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(create)))
                .andExpect(status().isCreated()).andReturn().getResponse();
        long id=json.readTree(response.getContentAsByteArray()).get("data").get("id").asLong();
        mvc.perform(csrf(post("/api/moderator/faqs"),moderator).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(create)))
                .andExpect(status().isConflict());
        var enrich=new Write("Curated FAQ","Reviewed answer\nAdditional curated answer","LIVING",true,List.of(),second[0],second[1]);
        mvc.perform(csrf(put("/api/moderator/faqs/"+id),moderator).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(enrich)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/moderator/faqs/suggestions").session(moderator).param("q",key))
                .andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/moderator/faqs/suggestions").session(moderator).param("q",key).param("processed","true"))
                .andExpect(jsonPath("$.data.total").value(2));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM faq_sources WHERE faq_id=?",Integer.class,id)).isEqualTo(2);
    }

    @Test void staleHiddenSourcesAndInvalidLinksCannotBePublished() throws Exception {
        var moderator=session("MODERATOR");long[] source=source(principal(moderator).id(),UUID.randomUUID().toString());
        jdbc.update("UPDATE comments SET status='HIDDEN' WHERE id=?",source[1]);
        mvc.perform(csrf(post("/api/moderator/faqs"),moderator).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(new Write("FAQ","Answer","LIVING",true,List.of(),source[0],source[1]))))
                .andExpect(status().isConflict());
        for(var resource:List.of(new Resource("EXTERNAL",null,"javascript:alert(1)","Bad"),new Resource("GUIDE",Long.MAX_VALUE,null,"Missing"))) {
            mvc.perform(csrf(post("/api/moderator/faqs"),moderator).contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsBytes(new Write("FAQ","Answer","LIVING",true,List.of(resource),null,null))))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(csrf(post("/api/moderator/faqs"),moderator).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(request(" ",true)))).andExpect(status().isBadRequest());
    }

    @Test void publicFaqResourcesStopLinkingToContentThatIsNoLongerPublic() throws Exception {
        var moderator=session("MODERATOR");String title=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO guide_items(stage_code,title,published,created_by) VALUES ('PRE',?,TRUE,?)",title,principal(moderator).id());
        long guide=jdbc.queryForObject("SELECT id FROM guide_items WHERE title=?",Long.class,title);
        var faq=service.save(null,new Write("Guide FAQ","Answer","PREPARATION",true,List.of(new Resource("GUIDE",guide,null,"Cẩm nang")),null,null),principal(moderator));
        mvc.perform(get("/api/faqs/"+faq.id())).andExpect(jsonPath("$.data.resources.length()").value(1));
        jdbc.update("UPDATE guide_items SET published=FALSE WHERE id=?",guide);
        mvc.perform(get("/api/faqs/"+faq.id())).andExpect(jsonPath("$.data.resources.length()").value(0));
    }
}
