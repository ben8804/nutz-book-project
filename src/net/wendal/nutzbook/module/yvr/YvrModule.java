package net.wendal.nutzbook.module.yvr;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import net.wendal.nutzbook.bean.User;
import net.wendal.nutzbook.bean.yvr.Topic;
import net.wendal.nutzbook.bean.yvr.TopicReply;
import net.wendal.nutzbook.bean.yvr.TopicType;
import net.wendal.nutzbook.module.BaseModule;
import net.wendal.nutzbook.mvc.CsrfActionFilter;
import net.wendal.nutzbook.util.Toolkit;

import org.nutz.dao.Chain;
import org.nutz.dao.Cnd;
import org.nutz.dao.pager.Pager;
import org.nutz.ioc.loader.annotation.Inject;
import org.nutz.ioc.loader.annotation.IocBean;
import org.nutz.lang.Files;
import org.nutz.lang.Lang;
import org.nutz.lang.Strings;
import org.nutz.lang.meta.Email;
import org.nutz.lang.random.R;
import org.nutz.lang.util.NutMap;
import org.nutz.log.Log;
import org.nutz.log.Logs;
import org.nutz.mvc.Mvcs;
import org.nutz.mvc.Scope;
import org.nutz.mvc.annotation.AdaptBy;
import org.nutz.mvc.annotation.At;
import org.nutz.mvc.annotation.Attr;
import org.nutz.mvc.annotation.By;
import org.nutz.mvc.annotation.Fail;
import org.nutz.mvc.annotation.Filters;
import org.nutz.mvc.annotation.GET;
import org.nutz.mvc.annotation.Ok;
import org.nutz.mvc.annotation.POST;
import org.nutz.mvc.annotation.Param;
import org.nutz.mvc.upload.TempFile;
import org.nutz.mvc.upload.UploadAdaptor;
import org.nutz.mvc.view.HttpStatusView;

@IocBean(create="init")
@At("/yvr")
@Fail("void")
public class YvrModule extends BaseModule {
	
	private static final Log log = Logs.get();
	
	protected byte[] emailKEY = R.sg(24).next().getBytes();
	
	@Inject("java:$conf.get(\"topic.image.dir\")")
	protected String imageDir;
	
	@At({"/", "/index"})
	@Ok(">>:/yvr/list")
	public void index() {}
	
	@GET
	@At
	@Ok("beetl:yvr/add.btl")
	public Object add(HttpServletRequest req) {
		NutMap re = new NutMap();
		re.put("types", TopicType.values());
		HttpSession session = req.getSession(false);
		
		if (session != null) {
			String csrf = Lang.md5(R.UU16());
			session.setAttribute("_csrf", csrf);
			re.put("_csrf", csrf);
		}
		return re;
	}

	@POST
	@At
	@Ok("json")
	@Filters(@By(type=CsrfActionFilter.class))
	public NutMap add(@Param("..")Topic topic,
					@Attr(scope=Scope.SESSION, value="me")int userId,
					HttpServletRequest req) {
		if (userId < 1) {
			//return ajaxFail("请先登录");
		}
		if (Strings.isBlank(topic.getTitle()) || topic.getTitle().length() > 1024 || topic.getTitle().length() < 10) {
			return ajaxFail("标题不合法");
		}
		if (Strings.isBlank(topic.getContent()) || topic.getContent().length() > 20000) {
			return ajaxFail("内容不合法");
		}
		if (topic.getTags() != null && topic.getTags().size() > 10) {
			return ajaxFail("最多只能有10个tag");
		}
		if (0 != dao.count(Topic.class, Cnd.where("title", "=", topic.getTitle().trim()))) {
			return ajaxFail("相同标题已经发过了");
		}
		topic.setUserId(userId);
		topic.setTop(false);
		if (topic.getType() == null)
			topic.setType(TopicType.ask);
		topic.setVisit(0);
		dao.insert(topic);
		
		return ajaxOk(topic.getId());
	}
	
	@At({"/list/?", "/list"})
	@GET
	@Ok("beetl:/yvr/index.btl")
	public Object list(TopicType type, @Param("..")Pager pager) {
		if (pager == null)
			pager = dao.createPager(1, 20);
		else {
			if (pager.getPageNumber() < 1)
				pager.setPageNumber(1);
			if (pager.getPageSize() > 50)
				pager.setPageSize(50);
		}
		Cnd cnd = Cnd.NEW();
		if (type != null)
			cnd.and("type", "=", type);
		List<Topic> list = dao.query(Topic.class, cnd, pager);
		for (Topic topic : list) {
			if (topic.getUserId() == 0)
				topic.setUserId(1);
			dao.fetchLinks(topic, null);
			dao.fetchLinks(topic.getAuthor(), null);
			topic.setReply(dao.count(TopicReply.class, Cnd.where("topicId", "=", topic.getId())));
			if (topic.getReply() > 0) {
				TopicReply reply = dao.fetch(TopicReply.class, Cnd.where("topicId", "=", topic.getId()).desc("createTime"));
				if (reply.getUserId() == 0)
					reply.setUserId(1);
				dao.fetchLinks(reply, "author");
				dao.fetchLinks(reply.getAuthor(), null);
				topic.setLastComment(reply);
			}
		}
		pager.setRecordCount(dao.count(Topic.class, cnd.desc("top")));
		NutMap re = new NutMap();
		re.put("list", list);
		re.put("pager", pager);
		re.put("type", type);
		re.put("types", TopicType.values());
		/**
		 	var page_start = current_page - 2 > 0 ? current_page - 2 : 1;
    		var page_end = page_start + 4 >= pages ? pages : page_start + 4;
		 */
		int page_start = pager.getPageNumber() - 2 > 0 ? pager.getPageNumber() - 2 : 1;
		int page_end = page_start + 4 >= pager.getPageCount() ? pager.getPageCount() : page_start + 4;
		re.put("page_start", page_start);
		re.put("page_end", page_end);
		re.put("current_page", pager.getPageNumber());
		re.put("pages", pager.getPageCount());
		
		return re;
	}
	
	@GET
	@At("/t/?")
	@Ok("beetl:yvr/topic.btl")
	public Object topic(int id, HttpServletRequest req) {
		Topic topic = dao.fetch(Topic.class, id);
		if (topic == null) {
			return HttpStatusView.HTTP_404;
		}
		if (topic.getUserId() == 0)
			topic.setUserId(1);
		dao.fetchLinks(topic, null);
		dao.fetchLinks(topic.getAuthor(), null);
		for (TopicReply reply : topic.getReplies()) {
			if (reply.getUserId() == 0)
				reply.setUserId(1);
			dao.fetchLinks(reply, null);
			dao.fetchLinks(reply.getAuthor(), null);
		}
		dao.update(Topic.class, Chain.makeSpecial("visit", "+1"), Cnd.where("id", "=", id));
		NutMap re = new NutMap();
		re.put("no_reply_topics", Collections.EMPTY_LIST);
		re.put("topic", topic);
		
		HttpSession session = req.getSession(false);
		if (session != null) {
			String csrf = Lang.md5(R.UU16());
			session.setAttribute("_csrf", csrf);
			re.put("_csrf", csrf);
		}
		
		return re;
	}
	
	@AdaptBy(type=UploadAdaptor.class, args={"${app.root}/WEB-INF/tmp2"})
	@POST
	@At
	@Ok("json")
	@Filters(@By(type=CsrfActionFilter.class))
	public Object upload(@Param("file")TempFile tmp,
			HttpServletRequest req,
			HttpServletResponse resp) throws IOException {
		resp.setContentType("application/json");
		NutMap jsonrpc = new NutMap();
		if (tmp == null || tmp.getFile().length() == 0) {
			return jsonrpc.setv("msg", "空文件");
		}
		if (tmp.getFile().length() > 2*1024*1024) {
			return jsonrpc.setv("msg", "文件太大了");
		}
		String id = R.UU32();
		String path = "/" + id.substring(0, 2) + "/" + id.substring(2);
		File f = new File(imageDir + path);
		Files.createNewFile(f);
		Files.copyFile(tmp.getFile(), f);
		jsonrpc.setv("url", req.getRequestURI() + path);
		jsonrpc.setv("success", true);
		return jsonrpc;
	}
	
	@Ok("raw:jpg")
	@At("/upload/?/?")
	@Fail("http:404")
	public Object image(String p, String p2) throws IOException {
		if ((p+p2).contains("."))
			return HttpStatusView.HTTP_404;
		File f = new File(imageDir, p + "/" + p2);
		return f;
	}

	@Filters(@By(type=CsrfActionFilter.class))
	@At("/t/?/reply")
	@Ok("json")
	public Object addReply(int topicId, @Param("..")TopicReply reply,
			@Attr(scope=Scope.SESSION, value="me")int userId) {
		if (reply == null || reply.getContent() == null || reply.getContent().trim().isEmpty()) {
			return ajaxFail("内容不能为空");
		}
		String cnt = reply.getContent().trim();
		if (cnt.length() < 2 || cnt.length() > 10000) {
			return ajaxFail("内容太长或太短了");
		}
		reply.setTopicId(topicId);
		reply.setUserId(userId);
		dao.insert(reply);
		return ajaxOk(null);
	}
	
	@Ok("raw:jpg")
	@At("/u/?/avatar")
	public File userAvatar(){
		return new File(Mvcs.getServletContext().getRealPath("/rs/user_avatar/none.jpg"));
	}
	
	/**
	 * 邮件回调的入口
	 * @param token 包含用户名和邮箱地址的加密内容
	 */
	@GET
	@At("/signup/?")
	@Ok("beetl:signup.btl")
	public void signup(String token) {
		
	}
	
	protected static Pattern P_USERNAME = Pattern.compile("[a-z][a-z0-9]{4,10}");
	
	@POST
	@At
	@Ok("json")
	public Object signup(@Param("email")String email, @Param("name")String name,
			HttpServletRequest req) {
		if (Strings.isBlank(name) || !P_USERNAME.matcher(name.toLowerCase()).find()) {
			return ajaxFail("用户名不合法");
		}
		int count = dao.count(User.class, Cnd.where("name", "=", name));
		if (count != 0) {
			return ajaxFail("用户名已经存在");
		}
		try {
			new Email(email);
		} catch (Exception e) {
			return ajaxFail("Email地址不合法");
		}
		try {
			String token = String.format("%s,%s,%s", name, email, System.currentTimeMillis());
			token = Toolkit._3DES_encode(emailKEY, token.getBytes());
			String url = req.getRequestURL() + "/" + token;
			String html = "<div>如果无法点击,请拷贝一下链接到浏览器中打开<p/>注册链接 %s</div>";
			html = String.format(html, url, url);
			emailService.send(email, "Nutz社区注册邮件", html);
			return ajaxOk("请查收邮件");
		} catch (Exception e) {
			return ajaxOk("发送邮件失败");
		}
	}
	
	public void init() {
		log.debug("Image Dir = " + imageDir);
		Files.createDirIfNoExists(new File(imageDir));
	}
}
