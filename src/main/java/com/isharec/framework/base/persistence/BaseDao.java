package com.isharec.framework.base.persistence;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;

import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.QueryWrapperFilter;
import org.apache.lucene.search.Sort;
import org.hibernate.Criteria;
import org.hibernate.Query;
import org.hibernate.SQLQuery;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Projections;
import org.hibernate.internal.CriteriaImpl;
import org.hibernate.search.FullTextQuery;
import org.hibernate.search.FullTextSession;
import org.hibernate.search.Search;
import org.hibernate.search.filter.impl.CachingWrapperFilter;
import org.hibernate.search.query.DatabaseRetrievalMethod;
import org.hibernate.search.query.ObjectLookupMethod;
import org.hibernate.transform.ResultTransformer;
import org.hibernate.transform.Transformers;
import org.springframework.beans.factory.annotation.Autowired;

import com.isharec.framework.utils.ReflectionsUtils;
import com.isharec.framework.utils.StringUtils;

public class BaseDao<T> {

	@Autowired
	private SessionFactory sessionFactory;

	private Class<?> entityClass;

	public BaseDao() {
		entityClass = ReflectionsUtils.getClassGenricType(getClass());
	}

	public Session getSession() {
		return sessionFactory.getCurrentSession();
	}

	public Session getOpenSession() {
		return sessionFactory.openSession();
	}

	/**
	 * 强制与数据库同步
	 */
	public void flush() {
		getSession().flush();
	}

	/**
	 * 清除缓存数据
	 */
	public void clear() {
		getSession().clear();
	}

	// -------------- QL Query --------------

	/**
	 * QL 分页查询
	 * 
	 * @param page
	 * @param qlString
	 * @return
	 */
	public <E> Page<E> find(Page<E> page, String qlString) {
		return find(page, qlString, null);
	}

	/**
	 * QL 分页查询
	 * 
	 * @param page
	 * @param qlString
	 * @param parameter
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public <E> Page<E> find(Page<E> page, String qlString, Parameter parameter) {
		// get count
		if (!page.isDisabled() && !page.isNotCount()) {
			String countQlString = "select count(*) "
					+ removeSelect(removeOrders(qlString));
			Query query = createQuery(countQlString, parameter);
			List<Object> list = query.list();
			if (list.size() > 0) {
				page.setCount(Long.valueOf(list.get(0).toString()));
			} else {
				page.setCount(list.size());
			}
			if (page.getCount() < 1) {
				return page;
			}
		}
		// order by
		String ql = qlString;
		if (StringUtils.isNotBlank(page.getOrderBy())) {
			ql += " order by " + page.getOrderBy();
		}
		Query query = createQuery(ql, parameter);
		// set page
		if (!page.isDisabled()) {
			query.setFirstResult(page.getFirstResult());
			query.setMaxResults(page.getMaxResults());
		}
		page.setList(query.list());
		return page;
	}

	/**
	 * QL 查询
	 * 
	 * @param qlString
	 * @return
	 */
	public <E> List<E> find(String qlString) {
		return find(qlString, null);
	}

	/**
	 * QL 查询
	 * 
	 * @param qlString
	 * @param parameter
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public <E> List<E> find(String qlString, Parameter parameter) {
		Query query = createQuery(qlString, parameter);
		return query.list();
	}

	@SuppressWarnings("unchecked")
	public <E> List<E> find(String qlString, Parameter parameter, int startNum,
			int limit) {
		Query query = createQuery(qlString, parameter);
		query.setCacheable(true);
		query.setFirstResult(startNum);
		query.setMaxResults(limit);
		return query.list();
	}

	public int getCount(String qlString, Parameter parameter) {
		Query query = createQuery(qlString, parameter);
		@SuppressWarnings("rawtypes")
		List list = query.list();
		if (list == null || list.size() == 0) {
			return 0;
		}
		return ((Long) (list.get(0))).intValue();
	}

	public int getCountBySql(String qlString, Parameter parameter) {
		Query query = createSqlQuery(qlString, parameter);
		@SuppressWarnings("rawtypes")
		List list = query.list();
		if (list == null) {
			return 0;
		}
		return ((BigInteger) (list.get(0))).intValue();
	}

	/**
	 * QL 查询所有
	 * 
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public List<T> findAll() {
		return getSession().createCriteria(entityClass).list();
	}

	/**
	 * 获取实体
	 * 
	 * @param id
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public T get(Serializable id) {
		return (T) getSession().get(entityClass, id);
	}

	/**
	 * 获取实体
	 * 
	 * @param qlString
	 * @return
	 */
	public T getByHql(String qlString) {
		return getByHql(qlString, null);
	}

	/**
	 * 获取实体
	 * 
	 * @param qlString
	 * @param parameter
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public T getByHql(String qlString, Parameter parameter) {
		Query query = createQuery(qlString, parameter);
		return (T) query.uniqueResult();
	}

	/**
	 * 保存实体
	 * 
	 * @param entity
	 */
	public void save(T entity) {
		try {
			// 获取实体编号
			Object id = null;
			for (Method method : entity.getClass().getMethods()) {
				Id idAnn = method.getAnnotation(Id.class);
				if (idAnn != null) {
					id = method.invoke(entity);
					break;
				}
			}
			// 插入前执行方法
			if (id == null || StringUtils.isBlank(id.toString())) {
				for (Method method : entity.getClass().getMethods()) {
					PrePersist pp = method.getAnnotation(PrePersist.class);
					if (pp != null) {
						method.invoke(entity);
						break;
					}
				}
			}
			// 更新前执行方法
			else {
				for (Method method : entity.getClass().getMethods()) {
					PreUpdate pu = method.getAnnotation(PreUpdate.class);
					if (pu != null) {
						method.invoke(entity);
						break;
					}
				}
			}
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		} catch (InvocationTargetException e) {
			e.printStackTrace();
		}
		getSession().saveOrUpdate(entity);
	}

	/**
	 * 保存实体列表
	 * 
	 * @param entityList
	 */
	public void save(List<T> entityList) {
		for (T entity : entityList) {
			save(entity);
		}
	}

	public void update(T entity) {
		getSession().update(entity);
	}

	/**
	 * 更新
	 * 
	 * @param qlString
	 * @return
	 */
	public int update(String qlString) {
		return update(qlString, null);
	}

	/**
	 * 更新
	 * 
	 * @param qlString
	 * @param parameter
	 * @return
	 */
	public int update(String qlString, Parameter parameter) {
		return createQuery(qlString, parameter).executeUpdate();
	}

	/**
	 * 物理删除
	 * 
	 * @param id
	 * @return
	 */
	public int delete(Serializable id) {
		return update("delete from " + entityClass.getSimpleName()
				+ "  where id = :p1", new Parameter(id));
	}

	public void delete(T entity) {
		getSession().delete(entity);
	}

	/**
	 * 更新删除标记
	 * 
	 * @param id
	 * @param delFlag
	 * @return
	 */
	public int updateDelFlag(Serializable id, String delFlag) {
		return update("update " + entityClass.getSimpleName()
				+ " set delFlag = :p2 where id = :p1", new Parameter(id,
				delFlag));
	}

	/**
	 * 创建 QL 查询对象
	 * 
	 * @param qlString
	 * @param parameter
	 * @return
	 */
	public Query createQuery(String qlString, Parameter parameter) {
		Query query = getSession().createQuery(qlString);
		setParameter(query, parameter);
		return query;
	}

	// -------------- SQL Query --------------

	/**
	 * SQL 分页查询
	 * 
	 * @param page
	 * @param sqlString
	 * @return
	 */
	public <E> Page<E> findBySql(Page<E> page, String sqlString) {
		return findBySql(page, sqlString, null, null);
	}

	/**
	 * SQL 分页查询
	 * 
	 * @param page
	 * @param sqlString
	 * @param parameter
	 * @return
	 */
	public <E> Page<E> findBySql(Page<E> page, String sqlString,
			Parameter parameter) {
		return findBySql(page, sqlString, parameter, null);
	}

	/**
	 * SQL 分页查询
	 * 
	 * @param page
	 * @param sqlString
	 * @param resultClass
	 * @return
	 */
	public <E> Page<E> findBySql(Page<E> page, String sqlString,
			Class<?> resultClass) {
		return findBySql(page, sqlString, null, resultClass);
	}

	/**
	 * SQL 分页查询
	 * 
	 * @param page
	 * @param sqlString
	 * @param resultClass
	 * @param parameter
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public <E> Page<E> findBySql(Page<E> page, String sqlString,
			Parameter parameter, Class<?> resultClass) {
		// get count
		if (!page.isDisabled() && !page.isNotCount()) {
			String countSqlString = "select count(*) "
					+ removeSelect(removeOrders(sqlString));
			// page.setCount(Long.valueOf(createSqlQuery(countSqlString,
			// parameter).uniqueResult().toString()));
			Query query = createSqlQuery(countSqlString, parameter);
			List<Object> list = query.list();
			if (list.size() > 0) {
				page.setCount(Long.valueOf(list.get(0).toString()));
			} else {
				page.setCount(list.size());
			}
			if (page.getCount() < 1) {
				return page;
			}
		}
		// order by
		String sql = sqlString;
		if (StringUtils.isNotBlank(page.getOrderBy())) {
			sql += " order by " + page.getOrderBy();
		}
		SQLQuery query = createSqlQuery(sql, parameter);
		// set page
		if (!page.isDisabled()) {
			query.setFirstResult(page.getFirstResult());
			query.setMaxResults(page.getMaxResults());
		}
		setResultTransformer(query, resultClass);
		page.setList(query.list());
		return page;
	}

	/**
	 * SQL 查询
	 * 
	 * @param sqlString
	 * @return
	 */
	public <E> List<E> findBySql(String sqlString) {
		return findBySql(sqlString, null, null);
	}

	/**
	 * SQL 查询
	 * 
	 * @param sqlString
	 * @param parameter
	 * @return
	 */
	public <E> List<E> findBySql(String sqlString, Parameter parameter) {
		return findBySql(sqlString, parameter, null);
	}

	/**
	 * SQL 查询
	 * 
	 * @param sqlString
	 * @param parameter
	 * 
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public <E> List<E> findBySql(String sqlString, Parameter parameter,
			int startNum, int limit) {

		Query query = this.getSession().createSQLQuery(sqlString);
		setParameter(query, parameter);
		query.setFirstResult(startNum);
		query.setMaxResults(limit);
		return query.list();
	}

	/**
	 * SQL 查询
	 * 
	 * @param sqlString
	 * @param resultClass
	 * @param parameter
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public <E> List<E> findBySql(String sqlString, Parameter parameter,
			Class<?> resultClass) {
		SQLQuery query = createSqlQuery(sqlString, parameter);
		setResultTransformer(query, resultClass);
		return query.list();
	}

	/**
	 * SQL 更新
	 * 
	 * @param sqlString
	 * @param parameter
	 * @return
	 */
	public int updateBySql(String sqlString, Parameter parameter) {
		return createSqlQuery(sqlString, parameter).executeUpdate();
	}

	/**
	 * 创建 SQL 查询对象
	 * 
	 * @param sqlString
	 * @param parameter
	 * @return
	 */
	public SQLQuery createSqlQuery(String sqlString, Parameter parameter) {
		SQLQuery query = getSession().createSQLQuery(sqlString);
		setParameter(query, parameter);
		return query;
	}

	// -------------- Query Tools --------------

	/**
	 * 设置查询结果类型
	 * 
	 * @param query
	 * @param resultClass
	 */
	private void setResultTransformer(SQLQuery query, Class<?> resultClass) {
		if (resultClass != null) {
			if (resultClass == Map.class) {
				query.setResultTransformer(Transformers.ALIAS_TO_ENTITY_MAP);
			} else if (resultClass == List.class) {
				query.setResultTransformer(Transformers.TO_LIST);
			} else {
				query.addEntity(resultClass);
			}
		}
	}

	/**
	 * 设置查询参数
	 * 
	 * @param query
	 * @param parameter
	 */
	private void setParameter(Query query, Parameter parameter) {
		if (parameter != null) {
			Set<String> keySet = parameter.keySet();
			for (String string : keySet) {
				Object value = parameter.get(string);
				// 这里考虑传入的参数是什么类型，不同类型使用的方法不同
				if (value instanceof Collection<?>) {
					query.setParameterList(string, (Collection<?>) value);
				} else if (value instanceof Object[]) {
					query.setParameterList(string, (Object[]) value);
				} else {
					query.setParameter(string, value);
				}
			}
		}
	}

	/**
	 * 去除qlString的select子句。
	 * 
	 * @param qlString
	 * @return
	 */
	private String removeSelect(String qlString) {
		int beginPos = qlString.toLowerCase().indexOf("from");
		return qlString.substring(beginPos);
	}

	/**
	 * 去除hql的orderBy子句。
	 * 
	 * @param qlString
	 * @return
	 */
	private String removeOrders(String qlString) {
		Pattern p = Pattern.compile("order\\s*by[\\w|\\W|\\s|\\S]*",
				Pattern.CASE_INSENSITIVE);
		Matcher m = p.matcher(qlString);
		StringBuffer sb = new StringBuffer();
		while (m.find()) {
			m.appendReplacement(sb, "");
		}
		m.appendTail(sb);
		return sb.toString();
	}

	// -------------- Criteria --------------

	/**
	 * 分页查询
	 * 
	 * @param page
	 * @return
	 */
	public Page<T> find(Page<T> page) {
		return find(page, createDetachedCriteria());
	}

	/**
	 * 使用检索标准对象分页查询
	 * 
	 * @param page
	 * @param detachedCriteria
	 * @return
	 */
	public Page<T> find(Page<T> page, DetachedCriteria detachedCriteria) {
		return find(page, detachedCriteria, Criteria.DISTINCT_ROOT_ENTITY,
				false);
	}

	public Page<T> findCacheable(Page<T> page, DetachedCriteria detachedCriteria) {
		return find(page, detachedCriteria, Criteria.DISTINCT_ROOT_ENTITY, true);
	}

	public Page<T> find(Page<T> page, String hql, Parameter parameter,
			int startNum, int limit) {
		// get count
		if (!page.isDisabled() && !page.isNotCount()) {
			String countHql = "select count(*) " + hql;
			List<Integer> list = find(countHql, parameter);
			int count = list == null ? 0 : list.get(0);
			page.setCount(count);
			if (page.getCount() < 1) {
				return page;
			}
		}
		// set page
		List<T> list = find(hql, parameter, startNum, limit);
		page.setList(list);
		return page;
	}

	/**
	 * 使用检索标准对象分页查询
	 * 
	 * @param page
	 * @param detachedCriteria
	 * @param resultTransformer
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public Page<T> find(Page<T> page, DetachedCriteria detachedCriteria,
			ResultTransformer resultTransformer, boolean cacheable) {
		// get count
		if (!page.isDisabled() && !page.isNotCount()) {
			page.setCount(count(detachedCriteria));
			if (page.getCount() < 1) {
				return page;
			}
		}
		Criteria criteria = detachedCriteria
				.getExecutableCriteria(getSession());
		if (cacheable) {
			criteria.setCacheable(cacheable);
		}
		criteria.setResultTransformer(resultTransformer);
		// set page
		if (!page.isDisabled()) {
			criteria.setFirstResult(page.getFirstResult());
			criteria.setMaxResults(page.getMaxResults());
		}
		// order by
		if (StringUtils.isNotBlank(page.getOrderBy())) {
			for (String order : StringUtils.split(page.getOrderBy(), ",")) {
				String[] o = StringUtils.split(order, " ");
				if (o.length == 1) {
					criteria.addOrder(Order.asc(o[0]));
				} else if (o.length == 2) {
					if ("DESC".equals(o[1].toUpperCase())) {
						criteria.addOrder(Order.desc(o[0]));
					} else {
						criteria.addOrder(Order.asc(o[0]));
					}
				}
			}
		}
		page.setList(criteria.list());
		return page;
	}

	/**
	 * 使用检索标准对象查询
	 * 
	 * @param detachedCriteria
	 * @return
	 */
	public List<T> find(DetachedCriteria detachedCriteria) {
		return find(detachedCriteria, Criteria.DISTINCT_ROOT_ENTITY);
	}

	/**
	 * 使用检索标准对象查询
	 * 
	 * @param detachedCriteria
	 * @param resultTransformer
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public List<T> find(DetachedCriteria detachedCriteria,
			ResultTransformer resultTransformer) {
		Criteria criteria = detachedCriteria
				.getExecutableCriteria(getSession());
		criteria.setResultTransformer(resultTransformer);
		return criteria.list();
	}

	/**
	 * 使用检索标准对象查询记录数
	 * 
	 * @param detachedCriteria
	 * @return
	 */
	@SuppressWarnings("rawtypes")
	public long count(DetachedCriteria detachedCriteria) {
		Criteria criteria = detachedCriteria
				.getExecutableCriteria(getSession());
		long totalCount = 0;
		try {
			// Get orders
			Field field = CriteriaImpl.class.getDeclaredField("orderEntries");
			field.setAccessible(true);
			List orderEntrys = (List) field.get(criteria);
			// Remove orders
			field.set(criteria, new ArrayList());
			// Get count
			criteria.setProjection(Projections.rowCount());
			totalCount = Long.valueOf(criteria.uniqueResult().toString());
			// Clean count
			criteria.setProjection(null);
			// Restore orders
			field.set(criteria, orderEntrys);
		} catch (NoSuchFieldException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		}
		return totalCount;
	}

	/**
	 * 创建与会话无关的检索标准对象
	 * 
	 * @param criterions
	 *            Restrictions.eq("name", value);
	 * @return
	 */
	public DetachedCriteria createDetachedCriteria(Criterion... criterions) {
		DetachedCriteria dc = DetachedCriteria.forClass(entityClass);
		for (Criterion c : criterions) {
			dc.add(c);
		}
		return dc;
	}

	// -------------- Hibernate search --------------

	/**
	 * 获取全文Session
	 */
	public FullTextSession getFullTextSession() {
		return Search.getFullTextSession(getSession());
	}

	/**
	 * 建立索引
	 */
	public void createIndex() {
		try {
			getFullTextSession().createIndexer(entityClass).startAndWait();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 全文检索
	 * 
	 * @param page
	 *            分页对象
	 * @param query
	 *            关键字查询对象
	 * @param queryFilter
	 *            查询过滤对象
	 * @param sort
	 *            排序对象
	 * @return 分页对象
	 */
	@SuppressWarnings("unchecked")
	public Page<T> search(Page<T> page, BooleanQuery query,
			BooleanQuery queryFilter, Sort sort) {

		// 按关键字查询
		FullTextQuery fullTextQuery = getFullTextSession().createFullTextQuery(
				query, entityClass);

		// 过滤无效的内容
		if (queryFilter != null) {
			fullTextQuery.setFilter(new CachingWrapperFilter(
					new QueryWrapperFilter(queryFilter)));
		}

		// 设置排序
		if (sort != null) {
			fullTextQuery.setSort(sort);
		}

		// 定义分页
		page.setCount(fullTextQuery.getResultSize());
		fullTextQuery.setFirstResult(page.getFirstResult());
		fullTextQuery.setMaxResults(page.getMaxResults());

		// 先从持久化上下文中查找对象，如果没有再从二级缓存中查找
		fullTextQuery.initializeObjectsWith(
				ObjectLookupMethod.SECOND_LEVEL_CACHE,
				DatabaseRetrievalMethod.QUERY);

		// 返回结果
		page.setList(fullTextQuery.list());

		return page;
	}

	/**
	 * 获取全文查询对象
	 */
	public BooleanQuery getFullTextQuery(BooleanClause... booleanClauses) {
		BooleanQuery booleanQuery = new BooleanQuery();
		for (BooleanClause booleanClause : booleanClauses) {
			booleanQuery.add(booleanClause);
		}
		return booleanQuery;
	}

	/**
	 * 获取全文查询对象
	 * 
	 * @param q
	 *            查询关键字
	 * @param fields
	 *            查询字段
	 * @return 全文查询对象
	 */
	/*
	 * public BooleanQuery getFullTextQuery(String q, String... fields) {
	 * Analyzer analyzer = new IKAnalyzer(); BooleanQuery query = new
	 * BooleanQuery(); try { if (StringUtils.isNotBlank(q)) { for (String field
	 * : fields) { QueryParser parser = new QueryParser(Version.LUCENE_36,
	 * field, analyzer); query.add(parser.parse(q), Occur.SHOULD); } } } catch
	 * (ParseException e) { e.printStackTrace(); } return query; }
	 */

	/**
	 * 设置关键字高亮
	 * 
	 * @param query
	 *            查询对象
	 * @param list
	 *            设置高亮的内容列表
	 * @param subLength
	 *            截取长度
	 * @param fields
	 *            字段名
	 */
	/*
	 * public List<T> keywordsHighlight(BooleanQuery query, List<T> list, int
	 * subLength, String... fields) { Analyzer analyzer = new IKAnalyzer();
	 * Formatter formatter = new SimpleHTMLFormatter(
	 * "<span class=\"highlight\">", "</span>"); Highlighter highlighter = new
	 * Highlighter(formatter, new QueryScorer( query));
	 * highlighter.setTextFragmenter(new SimpleFragmenter(subLength)); for (T
	 * entity : list) { try { for (String field : fields) { String text =
	 * StringUtils .replaceHtml((String) ReflectionsUtils .invokeGetter(entity,
	 * field)); String description = highlighter.getBestFragment(analyzer,
	 * field, text); if (description != null) {
	 * ReflectionsUtils.invokeSetter(entity, fields[0], description); break; }
	 * ReflectionsUtils.invokeSetter(entity, fields[0], StringUtils.abbr(text,
	 * subLength * 2)); } } catch (IOException e) { e.printStackTrace(); } catch
	 * (InvalidTokenOffsetsException e) { e.printStackTrace(); } } return list;
	 * }
	 */

	/**
	 * 是否存在表
	 * 
	 * @param tableName
	 * @return
	 */
	public boolean isExists(String tableName) {
		// 查询表是否存在
		String countSql = "select count(*) from information_schema.tables where table_name='"
				+ tableName + "'";
		int count = getCountBySql(countSql, null);
		return count > 0 ? true : false;
	}
}
