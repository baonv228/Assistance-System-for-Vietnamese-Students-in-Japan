package com.sep490.vsa.feature.faq;
import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.blog.ActiveAuthors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.sql.*;
import java.util.*;
import static com.sep490.vsa.feature.faq.FaqDtos.*;

@Service @Transactional(readOnly=true)
public class FaqService {
 private final JdbcTemplate jdbc;
 public FaqService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 private static final String SELECT="SELECT f.*,t.title AS topic_name FROM faq_entries f JOIN faq_topics t ON t.code=f.topic_code ";
 /** Question or answer holds the words as typed, accents and case ignored (as the Hỏi-Đáp search). */
 private static final String SEARCH=" AND (?='' OR CONCAT(f.question,' ',f.answer) COLLATE utf8mb4_0900_ai_ci LIKE ?) ";
 /** "Câu hỏi thường gặp liên quan": at most this many entries, the answer read up to this length. */
 static final int RELATED_MAX=5,RELATED_ANSWER_CHARS=3000,RELATED_SCAN=1000;
 private String query(String q){String value=q==null?"":q.trim();if(value.length()>200)throw bad("Từ khóa tối đa 200 ký tự.");return value;}
 private static String like(String search){return "%"+search.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";}
 private void paging(int page,int size){if(page<0||size<1||size>50)throw bad("Phân trang không hợp lệ.");}
 public List<Topic> topics(String q,boolean moderator){String search=query(q);return jdbc.query("""
 SELECT t.code,t.title,COUNT(f.id) FROM faq_topics t LEFT JOIN faq_entries f ON f.topic_code=t.code
 AND (?=TRUE OR f.published=TRUE)
 """+SEARCH+" GROUP BY t.code,t.title,t.sort_order ORDER BY t.sort_order",(r,n)->new Topic(r.getString(1),r.getString(2),r.getLong(3)),moderator,search,like(search));}
 public Page<Entry> list(String q,String topic,String status,int page,int size,boolean moderator){
  paging(page,size);String search=query(q),category=topic==null?"":topic.trim();
  if(!List.of("ALL","PUBLISHED","HIDDEN").contains(status))throw bad("Trạng thái không hợp lệ.");
  String where=" WHERE (?=TRUE OR f.published=TRUE) AND (?='ALL' OR f.published=?) AND (?='' OR f.topic_code=?) "+SEARCH;
  Object[] args={moderator,status,"PUBLISHED".equals(status),category,category,search,like(search)};
  long total=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM faq_entries f "+where,Long.class,args));
  int pages=(int)Math.ceil((double)total/size),current=Math.min(page,Math.max(0,pages-1));
  var params=new ArrayList<>(Arrays.asList(args));params.add(size);params.add((long)current*size);
  var items=jdbc.query(SELECT+where+" ORDER BY f.updated_at DESC,f.id DESC LIMIT ? OFFSET ?",(r,n)->map(r,moderator),params.toArray());
  return new Page<>(items,total,current,size,pages);
 }
 /**
  * Published entries that look like the text (a question being typed, or a search of Hỏi-Đáp), best first, see
  * {@link FaqMatcher}. Words of the text need not form one phrase of the entry, unlike {@link #list}.
  */
 public List<Entry> related(String q,int limit){
  var matcher=new FaqMatcher(query(q));
  if(matcher.isEmpty())return List.of();
  var candidates=jdbc.query("SELECT id,question,LEFT(answer,?) FROM faq_entries WHERE published=TRUE ORDER BY updated_at DESC,id DESC LIMIT ?",
    (r,n)->new FaqMatcher.Candidate(r.getLong(1),r.getString(2),r.getString(3)),RELATED_ANSWER_CHARS,RELATED_SCAN);
  var ids=matcher.rank(candidates,Math.max(1,Math.min(RELATED_MAX,limit)));
  // An entry hidden meanwhile is left out instead of failing the whole answer
  return ids.stream().flatMap(id->jdbc.query(SELECT+" WHERE f.id=? AND f.published=TRUE",(r,n)->map(r,false),id).stream()).toList();
 }
 public Entry detail(long id,boolean moderator){return jdbc.query(SELECT+" WHERE f.id=? AND (?=TRUE OR f.published=TRUE)",(r,n)->map(r,moderator),id,moderator).stream().findFirst().orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"FAQ không tồn tại hoặc đã bị ẩn."));}
 private Entry map(ResultSet r,boolean moderator)throws SQLException{
  long id=r.getLong("id");var resources=jdbc.query("SELECT kind,target_id,url,label FROM faq_resources WHERE faq_id=? ORDER BY sort_order,id",(s,n)->new Resource(s.getString(1),s.getObject(2,Long.class),s.getString(3),s.getString(4)),id);
  if(!moderator)resources=resources.stream().filter(this::available).toList();
  return new Entry(id,r.getString("question"),r.getString("answer"),r.getString("topic_code"),r.getString("topic_name"),r.getBoolean("published"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),resources);
 }
 private boolean available(Resource link){
  if("EXTERNAL".equals(link.kind()))return validUrl(link.url());
  if(link.targetId()==null)return false;
  String sql=switch(link.kind()){
   case "GUIDE"->"SELECT COUNT(*) FROM guide_items WHERE id=? AND published=TRUE AND deleted_at IS NULL";
   case "BLOG","QUESTION"->"SELECT COUNT(*) FROM blogs b WHERE b.id=? AND b.status='PUBLISHED' AND b.blog_type='"+("BLOG".equals(link.kind())?"POST":"QA")+"' AND "+ActiveAuthors.of("b");
   default->throw bad("Loại liên kết không hợp lệ.");};
  return Objects.requireNonNull(jdbc.queryForObject(sql,Integer.class,link.targetId()))>0;
 }
 private static boolean validUrl(String value){try{URI u=URI.create(value==null?"":value);return ("https".equalsIgnoreCase(u.getScheme())||"http".equalsIgnoreCase(u.getScheme()))&&u.getHost()!=null&&u.getUserInfo()==null;}catch(IllegalArgumentException e){return false;}}
 private void moderator(AppUserPrincipal me){if(me==null||!"MODERATOR".equals(me.role()))throw new ApiException(HttpStatus.FORBIDDEN,"Chỉ Moderator được quản lý FAQ.");}
 @Transactional public Entry save(Long id,Write request,AppUserPrincipal me){
  moderator(me);
  if(jdbc.queryForObject("SELECT COUNT(*) FROM faq_topics WHERE code=?",Integer.class,request.topicCode())==0)throw bad("Chủ đề không hợp lệ.");
  var links=request.resources()==null?List.<Resource>of():request.resources();
  for(var resource:links)if(!available(resource))throw bad("Liên kết không hợp lệ hoặc nội dung không còn công khai.");
  if((request.sourceQuestionId()==null)!=(request.sourceAnswerId()==null))throw bad("Thiếu thông tin câu hỏi/câu trả lời nguồn.");
  if(request.sourceQuestionId()!=null){
   var source=jdbc.query("SELECT b.id FROM blogs b JOIN comments c ON c.id=b.best_comment_id AND c.blog_id=b.id WHERE b.id=? AND c.id=? AND "+sourceVisible()+" FOR UPDATE",(r,n)->r.getLong(1),request.sourceQuestionId(),request.sourceAnswerId());
   if(source.isEmpty())throw new ApiException(HttpStatus.CONFLICT,"Câu trả lời tiêu biểu đã thay đổi hoặc không còn công khai. Hãy tải lại đề xuất.");
   if(jdbc.queryForObject("SELECT COUNT(*) FROM faq_sources WHERE question_id=? AND answer_id=?",Integer.class,request.sourceQuestionId(),request.sourceAnswerId())>0)throw new ApiException(HttpStatus.CONFLICT,"Đề xuất này đã được xử lý.");
  }
  long entryId;
  if(id==null){var key=new GeneratedKeyHolder();jdbc.update(c->{var ps=c.prepareStatement("INSERT INTO faq_entries(question,answer,topic_code,published,created_by,updated_by) VALUES (?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);ps.setString(1,request.question().trim());ps.setString(2,request.answer().trim());ps.setString(3,request.topicCode());ps.setBoolean(4,request.published());ps.setLong(5,me.id());ps.setLong(6,me.id());return ps;},key);entryId=Objects.requireNonNull(key.getKey()).longValue();}
  else{var found=jdbc.query("SELECT id FROM faq_entries WHERE id=? FOR UPDATE",(r,n)->r.getLong(1),id);if(found.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"Không tìm thấy FAQ.");entryId=id;jdbc.update("UPDATE faq_entries SET question=?,answer=?,topic_code=?,published=?,updated_by=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",request.question().trim(),request.answer().trim(),request.topicCode(),request.published(),me.id(),id);jdbc.update("DELETE FROM faq_resources WHERE faq_id=?",id);}
  for(int i=0;i<links.size();i++){var link=links.get(i);jdbc.update("INSERT INTO faq_resources(faq_id,kind,target_id,url,label,sort_order) VALUES (?,?,?,?,?,?)",entryId,link.kind(),"EXTERNAL".equals(link.kind())?null:link.targetId(),"EXTERNAL".equals(link.kind())?link.url():null,link.label().trim(),i);}
  if(request.sourceQuestionId()!=null)jdbc.update("INSERT INTO faq_sources(question_id,answer_id,faq_id,processed_by) VALUES (?,?,?,?)",request.sourceQuestionId(),request.sourceAnswerId(),entryId,me.id());
  return detail(entryId,true);
 }
 @Transactional public Entry visibility(long id,boolean published,AppUserPrincipal me){moderator(me);if(jdbc.update("UPDATE faq_entries SET published=?,updated_by=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",published,me.id(),id)==0)throw new ApiException(HttpStatus.NOT_FOUND,"Không tìm thấy FAQ.");return detail(id,true);}
 private String sourceVisible(){return "b.blog_type='QA' AND b.status='PUBLISHED' AND c.status='PUBLISHED' AND c.parent_id IS NULL AND "+ActiveAuthors.of("b")+" AND "+ActiveAuthors.of("c");}
 public Page<Suggestion> suggestions(String q,boolean processed,int page,int size){
  paging(page,size);String search=query(q);
  String from=" FROM blogs b JOIN comments c ON c.id=b.best_comment_id AND c.blog_id=b.id LEFT JOIN faq_sources fs ON fs.question_id=b.id AND fs.answer_id=c.id WHERE "+sourceVisible()+" AND (?=(fs.faq_id IS NOT NULL)) AND (?='' OR LOCATE(LOWER(?),LOWER(CONCAT(b.title,' ',c.content)))>0) ";
  long total=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,processed,search,search));int pages=(int)Math.ceil((double)total/size),current=Math.min(page,Math.max(0,pages-1));
  var items=jdbc.query("SELECT b.id,c.id,b.title,c.content,c.like_count,c.updated_at,fs.faq_id"+from+" ORDER BY c.like_count DESC,c.updated_at DESC,c.id DESC LIMIT ? OFFSET ?",(r,n)->new Suggestion(r.getLong(1),r.getLong(2),r.getString(3),r.getString(4),r.getInt(5),r.getTimestamp(6).toInstant(),r.getObject(7,Long.class)),processed,search,search,size,(long)current*size);
  return new Page<>(items,total,current,size,pages);
 }
 private static ApiException bad(String message){return new ApiException(HttpStatus.BAD_REQUEST,message);}
}
