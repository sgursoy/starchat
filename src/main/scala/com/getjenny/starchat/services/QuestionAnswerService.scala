package com.getjenny.starchat.services

/**
  * Created by Angelo Leto <angelo@getjenny.com> on 01/07/16.
  */

import akka.event.{Logging, LoggingAdapter}
import com.getjenny.analyzer.util.{RandomNumbers, Time}
import com.getjenny.starchat.SCActorSystem
import com.getjenny.starchat.entities._
import com.getjenny.starchat.services.esclient.QuestionAnswerElasticClient
import com.getjenny.starchat.utils.Index
import org.apache.lucene.search.join._
import org.elasticsearch.action.get.{GetResponse, MultiGetItemResponse, MultiGetRequest, MultiGetResponse}
import org.elasticsearch.action.index.{IndexRequest, IndexResponse}
import org.elasticsearch.action.search.{SearchRequest, SearchResponse, SearchType}
import org.elasticsearch.action.update.{UpdateRequest, UpdateResponse}
import org.elasticsearch.client.{RequestOptions, RestHighLevelClient}
import org.elasticsearch.common.unit.TimeValue
import org.elasticsearch.common.xcontent.XContentBuilder
import org.elasticsearch.common.xcontent.XContentFactory._
import org.elasticsearch.index.query.functionscore._
import org.elasticsearch.index.query.{BoolQueryBuilder, InnerHitBuilder, QueryBuilder, QueryBuilders}
import org.elasticsearch.rest.RestStatus
import org.elasticsearch.script._
import org.elasticsearch.search.aggregations.AggregationBuilders
import org.elasticsearch.search.aggregations.metrics.cardinality.Cardinality
import org.elasticsearch.search.aggregations.metrics.sum.Sum
import org.elasticsearch.search.builder.SearchSourceBuilder
import scalaz.Scalaz._

import scala.collection.JavaConverters._
import scala.collection.immutable.{List, Map}
import scala.collection.mutable
import scala.concurrent.Future

case class QuestionAnswerServiceException(message: String = "", cause: Throwable = None.orNull)
  extends Exception(message, cause)

trait QuestionAnswerService extends AbstractDataService {
  override val elasticClient: QuestionAnswerElasticClient

  val manausTermsExtractionService: ManausTermsExtractionService.type = ManausTermsExtractionService
  val log: LoggingAdapter = Logging(SCActorSystem.system, this.getClass.getCanonicalName)

  val nested_score_mode: Map[String, ScoreMode] = Map[String, ScoreMode]("min" -> ScoreMode.Min,
    "max" -> ScoreMode.Max, "avg" -> ScoreMode.Avg, "total" -> ScoreMode.Total)

  var cacheStealTimeMillis: Int

  private[this] def calcDictSize(indexName: String): DictSize = {
    val client: RestHighLevelClient = elasticClient.client

    val questionAgg = AggregationBuilders.cardinality("question_term_count").field("question.base")
    val answerAgg = AggregationBuilders.cardinality("answer_term_count").field("answer.base")

    val scriptBody = "def qnList = new ArrayList(doc[\"question.base\"].getValues()) ; " +
      "List anList = doc[\"answer.base\"].getValues() ; qnList.addAll(anList) ; return qnList ;"
    val script: Script = new Script(scriptBody)
    val totalAgg = AggregationBuilders.cardinality("total_term_count").script(script)

    val sourceReq: SearchSourceBuilder = new SearchSourceBuilder()
      .query(QueryBuilders.matchAllQuery)
      .size(0)
      .aggregation(questionAgg)
      .aggregation(answerAgg)
      .aggregation(totalAgg)


    val searchReq = new SearchRequest(Index.indexName(indexName, elasticClient.indexSuffix))
      .source(sourceReq)
      .types(elasticClient.indexMapping)
      .searchType(SearchType.DFS_QUERY_THEN_FETCH)
      .requestCache(true)

    val searchResp: SearchResponse = client.search(searchReq, RequestOptions.DEFAULT)

    val totalHits = searchResp.getHits.totalHits

    val questionAggRes: Cardinality = searchResp.getAggregations.get("question_term_count")
    val answerAggRes: Cardinality = searchResp.getAggregations.get("answer_term_count")
    val totalAggRes: Cardinality = searchResp.getAggregations.get("total_term_count")

    DictSize(numDocs = totalHits,
      question = questionAggRes.getValue,
      answer = answerAggRes.getValue,
      total = totalAggRes.getValue
    )
  }

  var dictSizeCacheMaxSize: Int
  private[this] val dictSizeCache: mutable.LinkedHashMap[String, (Long, DictSize)] =
    mutable.LinkedHashMap[String, (Long, DictSize)]()
  def dictSize(indexName: String, stale: Long = cacheStealTimeMillis): DictSize = {
    val key = indexName
    dictSizeCache.get(key) match {
      case Some((lastUpdateTs,dictSize)) =>
        val cacheStaleTime = math.abs(Time.timestampMillis - lastUpdateTs)
        if(cacheStaleTime < stale) {
          dictSize
        } else {
          val result = calcDictSize(indexName = indexName)
          if (dictSizeCache.size >= dictSizeCacheMaxSize) {
            dictSizeCache -= dictSizeCache.head._1
          }
          dictSizeCache.remove(key)
          dictSizeCache.update(key, (Time.timestampMillis, result))
          result
        }
      case _ =>
        val result = calcDictSize(indexName = indexName)
        if (dictSizeCache.size >= dictSizeCacheMaxSize) {
          dictSizeCache -= dictSizeCache.head._1
        }
        dictSizeCache.update(key, (Time.timestampMillis, result))
        result
    }
  }

  def dictSizeFuture(indexName: String, stale: Long = cacheStealTimeMillis): Future[DictSize] = Future {
    dictSize(indexName = indexName, stale = stale)
  }

  private[this] def calcTotalTerms(indexName: String): TotalTerms = {
    val client: RestHighLevelClient = elasticClient.client

    val questionAgg = AggregationBuilders.sum("question_term_count").field("question.base_length")
    val answerAgg = AggregationBuilders.sum("answer_term_count").field("answer.base_length")

    val sourceReq: SearchSourceBuilder = new SearchSourceBuilder()
      .query(QueryBuilders.matchAllQuery)
      .size(0)
      .aggregation(questionAgg)
      .aggregation(answerAgg)

    val searchReq = new SearchRequest(Index.indexName(indexName, elasticClient.indexSuffix))
      .source(sourceReq)
      .types(elasticClient.indexMapping)
      .searchType(SearchType.DFS_QUERY_THEN_FETCH)
      .requestCache(true)

    val searchResp: SearchResponse = client.search(searchReq, RequestOptions.DEFAULT)

    val totalHits = searchResp.getHits.totalHits

    val questionAggRes: Sum = searchResp.getAggregations.get("question_term_count")
    val answerAggRes: Sum = searchResp.getAggregations.get("answer_term_count")

    TotalTerms(numDocs = totalHits,
      question = questionAggRes.getValue.toLong,
      answer = answerAggRes.getValue.toLong)
  }

  var totalTermsCacheMaxSize: Int
  private[this] val totalTermsCache: mutable.LinkedHashMap[String, (Long, TotalTerms)] =
    mutable.LinkedHashMap[String, (Long, TotalTerms)]()
  def totalTerms(indexName: String, stale: Long = cacheStealTimeMillis): TotalTerms = {
    val key = indexName
    totalTermsCache.get(key) match {
      case Some((lastUpdateTs,dictSize)) =>
        val cacheStaleTime = math.abs(Time.timestampMillis - lastUpdateTs)
        if(cacheStaleTime < stale) {
          dictSize
        } else {
          val result = calcTotalTerms(indexName = indexName)
          if (totalTermsCache.size >= totalTermsCacheMaxSize) {
            totalTermsCache -= totalTermsCache.head._1
          }
          totalTermsCache.remove(key)
          totalTermsCache.update(key, (Time.timestampMillis, result))
          result
        }
      case _ =>
        val result = calcTotalTerms(indexName = indexName)
        if (totalTermsCache.size >= totalTermsCacheMaxSize) {
          totalTermsCache -= totalTermsCache.head._1
        }
        totalTermsCache.update(key, (Time.timestampMillis, result))
        result
    }
  }

  def totalTermsFuture(indexName: String, stale: Long = cacheStealTimeMillis): Future[TotalTerms] = Future {
    totalTerms(indexName = indexName, stale = stale)
  }

  def calcTermCount(indexName: String,
                    field: TermCountFields.Value = TermCountFields.question, term: String): TermCount = {
    val client: RestHighLevelClient = elasticClient.client

    val script: Script = new Script("_score")

    val agg = AggregationBuilders.sum("countTerms").script(script)

    val esFieldName: String = field match {
      case TermCountFields.question => "question.freq"
      case TermCountFields.answer => "answer.freq"
    }

    val boolQueryBuilder : BoolQueryBuilder = QueryBuilders.boolQuery()
      .must(QueryBuilders.matchQuery(esFieldName, term))

    val sourceReq: SearchSourceBuilder = new SearchSourceBuilder()
      .query(boolQueryBuilder)
      .size(0)
      .aggregation(agg)

    val searchReq = new SearchRequest(Index.indexName(indexName, elasticClient.indexSuffix))
      .source(sourceReq)
      .types(elasticClient.indexMapping)
      .searchType(SearchType.DFS_QUERY_THEN_FETCH)
      .requestCache(true)

    val searchResp: SearchResponse = client.search(searchReq, RequestOptions.DEFAULT)

    val totalHits = searchResp.getHits.totalHits

    val aggRes: Sum = searchResp.getAggregations.get("countTerms")

    TermCount(numDocs = totalHits,
      count = aggRes.getValue.toLong)
  }

  var countTermCacheMaxSize: Int
  private[this] val countTermCache: mutable.LinkedHashMap[String, (Long, TermCount)] =
    mutable.LinkedHashMap[String, (Long, TermCount)]()
  def termCount(indexName: String, field: TermCountFields.Value, term: String,
                stale: Long = cacheStealTimeMillis): TermCount = {
    val key = indexName + field + term
    countTermCache.get(key) match {
      case Some((lastUpdateTs,dictSize)) =>
        val cacheStaleTime = math.abs(Time.timestampMillis - lastUpdateTs)
        if(cacheStaleTime < stale) {
          dictSize
        } else {
          val result = calcTermCount(indexName = indexName, field = field, term = term)
          if (countTermCache.size > countTermCacheMaxSize) {
            countTermCache -= countTermCache.head._1
          }
          countTermCache.remove(key)
          countTermCache.update(key, (Time.timestampMillis, result))
          result
        }
      case _ =>
        val result = calcTermCount(indexName = indexName, field = field, term = term)
        if (countTermCache.size > countTermCacheMaxSize) {
          countTermCache -= countTermCache.head._1
        }
        countTermCache.update(key, (Time.timestampMillis, result))
        result
    }
  }

  def termCountFuture(indexName: String, field: TermCountFields.Value, term: String,
                      stale: Long = cacheStealTimeMillis): Future[TermCount] = Future {
    termCount(indexName, field, term, stale)
  }

  def countersCacheParameters(parameters: CountersCacheParameters): CountersCacheParameters = {
    parameters.dictSizeCacheMaxSize match {
      case Some(v) => this.dictSizeCacheMaxSize = v
      case _ => ;
    }

    parameters.totalTermsCacheMaxSize match {
      case Some(v) => this.totalTermsCacheMaxSize = v
      case _ => ;
    }

    parameters.countTermCacheMaxSize match {
      case Some(v) => this.countTermCacheMaxSize = v
      case _ => ;
    }

    parameters.cacheStealTimeMillis match {
      case Some(v) => this.cacheStealTimeMillis = v
      case _ => ;
    }

    CountersCacheParameters(
      dictSizeCacheMaxSize = Some(dictSizeCacheMaxSize),
      totalTermsCacheMaxSize = Some(totalTermsCacheMaxSize),
      countTermCacheMaxSize = Some(countTermCacheMaxSize),
      cacheStealTimeMillis = Some(cacheStealTimeMillis)
    )
  }

  def countersCacheParameters: (CountersCacheParameters, CountersCacheSize) = {
    (CountersCacheParameters(
      dictSizeCacheMaxSize = Some(dictSizeCacheMaxSize),
      totalTermsCacheMaxSize = Some(totalTermsCacheMaxSize),
      countTermCacheMaxSize = Some(countTermCacheMaxSize),
      cacheStealTimeMillis = Some(cacheStealTimeMillis)
    ),
      CountersCacheSize(
        dictSizeCacheSize = dictSizeCache.size,
        totalTermsCacheSize = totalTermsCache.size,
        countTermCacheSize = countTermCache.size
      ))
  }


  def countersCacheReset: (CountersCacheParameters, CountersCacheSize) = {
    dictSizeCache.clear()
    countTermCache.clear()
    totalTermsCache.clear()
    countersCacheParameters
  }

  def search(indexName: String, documentSearch: KBDocumentSearch): Future[Option[SearchKBDocumentsResults]] = {
    val client: RestHighLevelClient = elasticClient.client

    val sourceReq: SearchSourceBuilder = new SearchSourceBuilder()
      .from(documentSearch.from.getOrElse(0))
      .size(documentSearch.size.getOrElse(10))
      .minScore(documentSearch.minScore.getOrElse(Option{elasticClient.queryMinThreshold}.getOrElse(0.0f)))

    val searchReq = new SearchRequest(Index.indexName(indexName, elasticClient.indexSuffix))
      .source(sourceReq)
      .types(elasticClient.indexMapping)
      .searchType(SearchType.DFS_QUERY_THEN_FETCH)

    val boolQueryBuilder : BoolQueryBuilder = QueryBuilders.boolQuery()

    documentSearch.doctype match {
      case Some(doctype) => boolQueryBuilder.filter(QueryBuilders.termQuery("doctype", doctype))
      case _ => ;
    }

    documentSearch.verified match {
      case Some(verified) => boolQueryBuilder.filter(QueryBuilders.termQuery("verified", verified))
      case _ => ;
    }

    documentSearch.topics match {
      case Some(topics) => boolQueryBuilder.filter(QueryBuilders.termQuery("topics.base", topics))
      case _ => ;
    }

    documentSearch.dclass match {
      case Some(dclass) => boolQueryBuilder.filter(QueryBuilders.termQuery("dclass", dclass))
      case _ => ;
    }

    documentSearch.state match {
      case Some(state) => boolQueryBuilder.filter(QueryBuilders.termQuery("state", state))
      case _ => ;
    }

    documentSearch.status match {
      case Some(status) => boolQueryBuilder.filter(QueryBuilders.termQuery("status", status))
      case _ => ;
    }

    documentSearch.question match {
      case Some(questionQuery) =>
        boolQueryBuilder.must(QueryBuilders.boolQuery()
          .must(QueryBuilders.matchQuery("question.stem", questionQuery))
          .should(QueryBuilders.matchPhraseQuery("question.raw", questionQuery)
            .boost(elasticClient.questionExactMatchBoost))
        )

        val questionNegativeNestedQuery: QueryBuilder = QueryBuilders.nestedQuery(
          "question_negative",
          QueryBuilders.matchQuery("question_negative.query.base", questionQuery)
            .minimumShouldMatch(elasticClient.questionNegativeMinimumMatch)
            .boost(elasticClient.questionNegativeBoost),
          ScoreMode.Total
        ).ignoreUnmapped(true)
          .innerHit(new InnerHitBuilder().setSize(100))

        boolQueryBuilder.should(
          questionNegativeNestedQuery
        )
      case _ => ;
    }

    documentSearch.random.filter(identity) match {
      case Some(true) =>
        val randomBuilder = new RandomScoreFunctionBuilder().seed(RandomNumbers.integer)
        val functionScoreQuery: QueryBuilder = QueryBuilders.functionScoreQuery(randomBuilder)
        boolQueryBuilder.must(functionScoreQuery)
      case _ => ;
    }

    documentSearch.questionScoredTerms match {
      case Some(questionScoredTerms) =>
        val queryTerms = QueryBuilders.boolQuery()
          .should(QueryBuilders.matchQuery("question_scored_terms.term", questionScoredTerms))
        val script: Script = new Script("doc[\"question_scored_terms.score\"].value")
        val scriptFunction = new ScriptScoreFunctionBuilder(script)
        val functionScoreQuery: QueryBuilder = QueryBuilders.functionScoreQuery(queryTerms, scriptFunction)

        val nestedQuery: QueryBuilder = QueryBuilders.nestedQuery(
          "question_scored_terms",
          functionScoreQuery,
          nested_score_mode.getOrElse(elasticClient.queriesScoreMode, ScoreMode.Total)
        ).ignoreUnmapped(true).innerHit(new InnerHitBuilder().setSize(100))
        boolQueryBuilder.should(nestedQuery)
      case _ => ;
    }

    documentSearch.answer match {
      case Some(value) =>
        boolQueryBuilder.must(QueryBuilders.matchQuery("answer.stem", value))
      case _ => ;
    }

    documentSearch.conversation match {
      case Some(value) =>
        boolQueryBuilder.must(QueryBuilders.matchQuery("conversation", value))
      case _ => ;
    }

    sourceReq.query(boolQueryBuilder)

    val searchResp: SearchResponse = client.search(searchReq, RequestOptions.DEFAULT)

    val documents : Option[List[SearchKBDocument]] = Option { searchResp.getHits.getHits.toList.map { item =>

      // val fields : Map[String, GetField] = item.getFields.toMap
      val id : String = item.getId

      // val score : Float = fields.get("_score").asInstanceOf[Float]
      val source : Map[String, Any] = item.getSourceAsMap.asScala.toMap

      val conversation : String = source.get("conversation") match {
        case Some(t) => t.asInstanceOf[String]
        case None => ""
      }

      val indexInConversation : Option[Int] = source.get("index_in_conversation") match {
        case Some(t) => Option { t.asInstanceOf[Int] }
        case None => None : Option[Int]
      }

      val question : String = source.get("question") match {
        case Some(t) => t.asInstanceOf[String]
        case None => ""
      }

      val questionNegative : Option[List[String]] = source.get("question_negative") match {
        case Some(t) =>
          val res = t.asInstanceOf[java.util.ArrayList[java.util.HashMap[String, String]]]
            .asScala.map(_.asScala.get("query")).filter(_.nonEmpty).map(_.get).toList
          Option { res }
        case None => None: Option[List[String]]
      }

      val questionScoredTerms: Option[List[(String, Double)]] = source.get("question_scored_terms") match {
        case Some(t) => Option {
          t.asInstanceOf[java.util.ArrayList[java.util.HashMap[String, Any]]].asScala
            .map(pair =>
              (pair.getOrDefault("term", "").asInstanceOf[String],
                pair.getOrDefault("score", 0.0).asInstanceOf[Double]))
            .toList
        }
        case None => None : Option[List[(String, Double)]]
      }

      val answer : String = source.get("answer") match {
        case Some(t) => t.asInstanceOf[String]
        case None => ""
      }

      val answerScoredTerms: Option[List[(String, Double)]] = source.get("answer_scored_terms") match {
        case Some(t) => Option {
          t.asInstanceOf[java.util.ArrayList[java.util.HashMap[String, Any]]].asScala
            .map(pair =>
              (pair.getOrDefault("term", "").asInstanceOf[String],
                pair.getOrDefault("score", 0.0).asInstanceOf[Double]))
            .toList
        }
        case None => None : Option[List[(String, Double)]]
      }

      val verified : Boolean = source.get("verified") match {
        case Some(t) => t.asInstanceOf[Boolean]
        case None => false
      }

      val topics : Option[String] = source.get("topics") match {
        case Some(t) => Option { t.asInstanceOf[String] }
        case None => None : Option[String]
      }

      val dclass : Option[String] = source.get("dclass") match {
        case Some(t) => Option { t.asInstanceOf[String] }
        case None => None : Option[String]
      }

      val doctype : String = source.get("doctype") match {
        case Some(t) => t.asInstanceOf[String]
        case None => doctypes.normal
      }

      val state : Option[String] = source.get("state") match {
        case Some(t) => Option { t.asInstanceOf[String] }
        case None => None : Option[String]
      }

      val status : Int = source.get("status") match {
        case Some(t) => t.asInstanceOf[Int]
        case None => 0
      }

      val document : KBDocument = KBDocument(id = id, conversation = conversation,
        indexInConversation = indexInConversation, question = question,
        questionNegative = questionNegative,
        questionScoredTerms = questionScoredTerms,
        answer = answer,
        answerScoredTerms = answerScoredTerms,
        verified = verified,
        topics = topics,
        dclass = dclass,
        doctype = doctype,
        state = state,
        status = status)

      val searchDocument : SearchKBDocument = SearchKBDocument(score = item.getScore, document = document)
      searchDocument
    }
    }

    val filteredDoc : List[SearchKBDocument] = documents.getOrElse(List[SearchKBDocument]())

    val maxScore : Float = searchResp.getHits.getMaxScore
    val total : Int = filteredDoc.length
    val searchResults : SearchKBDocumentsResults = SearchKBDocumentsResults(total = total, maxScore = maxScore,
      hits = filteredDoc)

    val searchResultsOption : Future[Option[SearchKBDocumentsResults]] = Future { Option { searchResults } }
    searchResultsOption
  }

  def create(indexName: String, document: KBDocument, refresh: Int): Future[Option[IndexDocumentResult]] = Future {
    val builder : XContentBuilder = jsonBuilder().startObject()

    builder.field("id", document.id)
    builder.field("conversation", document.conversation)

    document.indexInConversation match {
      case Some(t) => builder.field("index_in_conversation", t)
      case None => ;
    }

    builder.field("question", document.question)

    document.questionNegative match {
      case Some(t) =>
        val array = builder.startArray("question_negative")
        t.foreach(q => {
          array.startObject().field("query", q).endObject()
        })
        array.endArray()
      case None => ;
    }

    document.questionScoredTerms match {
      case Some(t) =>
        val array = builder.startArray("question_scored_terms")
        t.foreach{case(term, score) =>
          array.startObject().field("term", term)
            .field("score", score).endObject()
        }
        array.endArray()
      case None => ;
    }

    builder.field("answer", document.answer)

    document.answerScoredTerms match {
      case Some(t) =>
        val array = builder.startArray("answer_scored_terms")
        t.foreach{case(term, score) =>
          array.startObject().field("term", term)
            .field("score", score).endObject()
        }
        array.endArray()
      case None => ;
    }

    builder.field("verified", document.verified)

    document.topics match {
      case Some(t) => builder.field("topics", t)
      case None => ;
    }
    builder.field("doctype", document.doctype)

    document.dclass match {
      case Some(t) => builder.field("dclass", t)
      case None => ;
    }
    document.state match {
      case Some(t) => builder.field("state", t)
      case None => ;
    }

    builder.field("status", document.status)

    builder.endObject()

    val client: RestHighLevelClient = elasticClient.client

    val indexReq = new IndexRequest()
      .index(Index.indexName(indexName, elasticClient.indexSuffix))
      .`type`(elasticClient.indexMapping)
      .id(document.id)
      .source(builder)

    val response: IndexResponse = client.index(indexReq, RequestOptions.DEFAULT)

    if (refresh =/= 0) {
      val refresh_index = elasticClient.refresh(Index.indexName(indexName, elasticClient.indexSuffix))
      if(refresh_index.failedShardsN > 0) {
        throw QuestionAnswerServiceException("index refresh failed: (" + indexName + ")")
      }
    }

    val doc_result: IndexDocumentResult = IndexDocumentResult(index = response.getIndex,
      dtype = response.getType,
      id = response.getId,
      version = response.getVersion,
      created = response.status === RestStatus.CREATED
    )

    Option {doc_result}
  }

  def update(indexName: String, id: String, document: KBDocumentUpdate, refresh: Int): UpdateDocumentResult = {
    val builder : XContentBuilder = jsonBuilder().startObject()

    document.conversation match {
      case Some(t) => builder.field("conversation", t)
      case None => ;
    }

    document.question match {
      case Some(t) => builder.field("question", t)
      case None => ;
    }

    document.questionNegative match {
      case Some(t) =>
        val array = builder.startArray("question_negative")
        t.foreach(q => {
          array.startObject().field("query", q).endObject()
        })
        array.endArray()
      case None => ;
    }

    document.questionScoredTerms match {
      case Some(t) =>
        val array = builder.startArray("question_scored_terms")
        t.foreach{case(term, score) =>
          array.startObject().field("term", term)
            .field("score", score).endObject()
        }
        array.endArray()
      case None => ;
    }

    document.indexInConversation match {
      case Some(t) => builder.field("index_in_conversation", t)
      case None => ;
    }

    document.answer match {
      case Some(t) => builder.field("answer", t)
      case None => ;
    }

    document.answerScoredTerms match {
      case Some(t) =>
        val array = builder.startArray("answer_scored_terms")
        t.foreach{
          case(term, score) =>
            array.startObject().field("term", term).field("score", score).endObject()
        }
        array.endArray()
      case None => ;
    }

    document.verified match {
      case Some(t) => builder.field("verified", t)
      case None => ;
    }

    document.topics match {
      case Some(t) => builder.field("topics", t)
      case None => ;
    }

    document.dclass match {
      case Some(t) => builder.field("dclass", t)
      case None => ;
    }

    document.doctype match {
      case Some(t) => builder.field("doctype", t)
      case None => ;
    }

    document.state match {
      case Some(t) => builder.field("state", t)
      case None => ;
    }

    document.status match {
      case Some(t) => builder.field("status", t)
      case None => ;
    }

    builder.endObject()

    val client: RestHighLevelClient = elasticClient.client

    val updateReq = new UpdateRequest()
      .index(Index.indexName(indexName, elasticClient.indexSuffix))
      .`type`(elasticClient.indexMapping)
      .doc(builder)
      .id(id)

    val response: UpdateResponse = client.update(updateReq, RequestOptions.DEFAULT)

    if (refresh =/= 0) {
      val refresh_index = elasticClient.refresh(Index.indexName(indexName, elasticClient.indexSuffix))
      if(refresh_index.failedShardsN > 0) {
        throw QuestionAnswerServiceException("index refresh failed: (" + indexName + ")")
      }
    }

    val docResult: UpdateDocumentResult = UpdateDocumentResult(index = response.getIndex,
      dtype = response.getType,
      id = response.getId,
      version = response.getVersion,
      created = response.status === RestStatus.CREATED
    )

    docResult
  }

  def read(indexName: String, ids: List[String]): Option[SearchKBDocumentsResults] = {
    val client: RestHighLevelClient = elasticClient.client

    val multigetReq = new MultiGetRequest()
    ids.foreach{id =>
      multigetReq.add(
        new MultiGetRequest.Item(Index.indexName(indexName, elasticClient.indexSuffix), elasticClient.indexSuffix, id)
      )
    }

    val response: MultiGetResponse = client.mget(multigetReq, RequestOptions.DEFAULT)

    val documents : Option[List[SearchKBDocument]] = Option { response.getResponses
      .toList.filter((p: MultiGetItemResponse) => p.getResponse.isExists).map { e =>

      val item: GetResponse = e.getResponse

      val id : String = item.getId

      val source : Map[String, Any] = item.getSource.asScala.toMap

      val conversation : String = source.get("conversation") match {
        case Some(t) => t.asInstanceOf[String]
        case None => ""
      }

      val indexInConversation : Option[Int] = source.get("index_in_conversation") match {
        case Some(t) => Option { t.asInstanceOf[Int] }
        case None => None : Option[Int]
      }

      val question : String = source.get("question") match {
        case Some(t) => t.asInstanceOf[String]
        case None => ""
      }

      val questionNegative : Option[List[String]] = source.get("question_negative") match {
        case Some(t) =>
          val res = t.asInstanceOf[java.util.ArrayList[java.util.HashMap[String, String]]]
            .asScala.map(_.getOrDefault("query", None.orNull)).filter(_ =/= None.orNull).toList
          Option { res }
        case None => None: Option[List[String]]
      }

      val questionScoredTerms: Option[List[(String, Double)]] = source.get("question_scored_terms") match {
        case Some(t) =>
          Option {
            t.asInstanceOf[java.util.ArrayList[java.util.HashMap[String, Any]]]
              .asScala.map(_.asScala.toMap)
              .map(term => (term.getOrElse("term", "").asInstanceOf[String],
                term.getOrElse("score", 0.0).asInstanceOf[Double])).filter { case (term, _) => term =/= "" }.toList
          }
        case None => None : Option[List[(String, Double)]]
      }

      val answer : String = source.get("answer") match {
        case Some(t) => t.asInstanceOf[String]
        case None => ""
      }

      val answerScoredTerms: Option[List[(String, Double)]] = source.get("answer_scored_terms") match {
        case Some(t) =>
          Option {
            t.asInstanceOf[java.util.ArrayList[java.util.HashMap[String, Any]]]
              .asScala.map(_.asScala.toMap)
              .map(term => (term.getOrElse("term", "").asInstanceOf[String],
                term.getOrElse("score", 0.0).asInstanceOf[Double]))
              .filter{case(term,_) => term =/= ""}.toList
          }
        case None => None : Option[List[(String, Double)]]
      }

      val verified : Boolean = source.get("verified") match {
        case Some(t) => t.asInstanceOf[Boolean]
        case None => false
      }

      val topics : Option[String] = source.get("topics") match {
        case Some(t) => Option { t.asInstanceOf[String] }
        case None => None : Option[String]
      }

      val dclass : Option[String] = source.get("dclass") match {
        case Some(t) => Option { t.asInstanceOf[String] }
        case None => None : Option[String]
      }

      val doctype : String = source.get("doctype") match {
        case Some(t) => t.asInstanceOf[String]
        case None => doctypes.normal
      }

      val state : Option[String] = source.get("state") match {
        case Some(t) => Option { t.asInstanceOf[String] }
        case None => None : Option[String]
      }

      val status : Int = source.get("status") match {
        case Some(t) => t.asInstanceOf[Int]
        case None => 0
      }

      val document : KBDocument = KBDocument(id = id, conversation = conversation,
        indexInConversation = indexInConversation,
        question = question,
        questionNegative = questionNegative,
        questionScoredTerms = questionScoredTerms,
        answer = answer,
        answerScoredTerms = answerScoredTerms,
        verified = verified,
        topics = topics,
        dclass = dclass,
        doctype = doctype,
        state = state,
        status = status)

      val searchDocument : SearchKBDocument = SearchKBDocument(score = .0f, document = document)
      searchDocument
    }
    }

    val filteredDoc : List[SearchKBDocument] = documents.getOrElse(List[SearchKBDocument]())

    val maxScore : Float = .0f
    val total : Int = filteredDoc.length
    val searchResults : SearchKBDocumentsResults = SearchKBDocumentsResults(total = total, maxScore = maxScore,
      hits = filteredDoc)

    val searchResultsOption : Option[SearchKBDocumentsResults] = Option { searchResults }
    searchResultsOption
  }

  def readFuture(indexName: String, ids: List[String]): Future[Option[SearchKBDocumentsResults]] = Future {
    read(indexName, ids)
  }

  def updateFuture(indexName: String, id: String, document: KBDocumentUpdate, refresh: Int):
  Future[UpdateDocumentResult] = Future {
    update(indexName, id, document, refresh)
  }

  def allDocuments(indexName: String, keepAlive: Long = 60000, size: Int = 100): Iterator[KBDocument] = {
    val client: RestHighLevelClient = elasticClient.client

    val sourceReq: SearchSourceBuilder = new SearchSourceBuilder()
      .query(QueryBuilders.matchAllQuery)
      .size(size)

    val searchReq = new SearchRequest(Index.indexName(indexName, elasticClient.indexSuffix))
      .source(sourceReq)
      .types(elasticClient.indexMapping)
      .scroll(new TimeValue(keepAlive))

    var scrollResp: SearchResponse = client.search(searchReq, RequestOptions.DEFAULT)

    val iterator = Iterator.continually{
      val documents = scrollResp.getHits.getHits.toList.map { e =>

          val id : String = e.getId
          val source : Map[String, Any] = e.getSourceAsMap.asScala.toMap

          val conversation : String = source.get("conversation") match {
            case Some(t) => t.asInstanceOf[String]
            case None => ""
          }

          val indexInConversation : Option[Int] = source.get("index_in_conversation") match {
            case Some(t) => Option { t.asInstanceOf[Int] }
            case None => None : Option[Int]
          }

          val question : String = source.get("question") match {
            case Some(t) => t.asInstanceOf[String]
            case None => ""
          }

          val questionNegative : Option[List[String]] = source.get("question_negative") match {
            case Some(t) =>
              val res = t.asInstanceOf[java.util.ArrayList[java.util.HashMap[String, String]]]
                .asScala.map(_.getOrDefault("query", None.orNull)).filter(_ =/= None.orNull).toList
              Option { res }
            case None => None: Option[List[String]]
          }

          val questionScoredTerms: Option[List[(String, Double)]] = source.get("question_scored_terms") match {
            case Some(t) =>
              Option {
                t.asInstanceOf[java.util.ArrayList[java.util.HashMap[String, Any]]]
                  .asScala.map(_.asScala.toMap)
                  .map(term => (term.getOrElse("term", "").asInstanceOf[String],
                    term.getOrElse("score", 0.0).asInstanceOf[Double])).filter { case (term, _) => term =/= "" }.toList
              }
            case None => None : Option[List[(String, Double)]]
          }

          val answer : String = source.get("answer") match {
            case Some(t) => t.asInstanceOf[String]
            case None => ""
          }

          val answerScoredTerms: Option[List[(String, Double)]] = source.get("answer_scored_terms") match {
            case Some(t) =>
              Option {
                t.asInstanceOf[java.util.ArrayList[java.util.HashMap[String, Any]]]
                  .asScala.map(_.asScala.toMap)
                  .map(term => (term.getOrElse("term", "").asInstanceOf[String],
                    term.getOrElse("score", 0.0).asInstanceOf[Double]))
                  .filter{case(term,_) => term =/= ""}.toList
              }
            case None => None : Option[List[(String, Double)]]
          }

          val verified : Boolean = source.get("verified") match {
            case Some(t) => t.asInstanceOf[Boolean]
            case None => false
          }

          val topics : Option[String] = source.get("topics") match {
            case Some(t) => Option { t.asInstanceOf[String] }
            case None => None : Option[String]
          }

          val dclass : Option[String] = source.get("dclass") match {
            case Some(t) => Option { t.asInstanceOf[String] }
            case None => None : Option[String]
          }

          val doctype : String = source.get("doctype") match {
            case Some(t) => t.asInstanceOf[String]
            case None => doctypes.normal
          }

          val state : Option[String] = source.get("state") match {
            case Some(t) => Option { t.asInstanceOf[String] }
            case None => None : Option[String]
          }

          val status : Int = source.get("status") match {
            case Some(t) => t.asInstanceOf[Int]
            case None => 0
          }

          KBDocument(id = id, conversation = conversation,
            indexInConversation = indexInConversation,
            question = question,
            questionNegative = questionNegative,
            questionScoredTerms = questionScoredTerms,
            answer = answer,
            answerScoredTerms = answerScoredTerms,
            verified = verified,
            topics = topics,
            dclass = dclass,
            doctype = doctype,
            state = state,
            status = status)
      }

      scrollResp = client.search(searchReq, RequestOptions.DEFAULT)
      (documents, documents.nonEmpty)
    }.takeWhile{case (_, docNonEmpty) => docNonEmpty}
    iterator
      .flatMap{case (doc, _) => doc}
  }

  private[this] def extractionReq(text: String, er: UpdateQATermsRequest) = TermsExtractionRequest(text = text,
    tokenizer = Some("space_punctuation"),
    commonOrSpecificSearchPrior = Some(CommonOrSpecificSearch.COMMON),
    commonOrSpecificSearchObserved = Some(CommonOrSpecificSearch.IDXSPECIFIC),
    observedDataSource = Some(ObservedDataSources.KNOWLEDGEBASE),
    fieldsPrior = Some(TermCountFields.all),
    fieldsObserved = Some(TermCountFields.all),
    minWordsPerSentence = Some(10),
    pruneTermsThreshold = Some(100000),
    misspellMaxOccurrence = Some(5),
    activePotentialDecay = Some(10),
    activePotential = Some(true),
    totalInfo = Some(false))

  def updateTextTerms(indexName: String,
                      extractionRequest: UpdateQATermsRequest
                     ): List[UpdateDocumentResult] = {

    val ids: List[String] = List(extractionRequest.id)
    val q = this.read(indexName, ids)
    val hits = q.getOrElse(SearchKBDocumentsResults())
    hits.hits.map { hit =>
        val extractionReqQ = extractionReq(text = hit.document.question, er = extractionRequest)
        val extractionReqA = extractionReq(text = hit.document.answer, er = extractionRequest)
        val (_, termsQ) = manausTermsExtractionService
          .textTerms(indexName = indexName ,extractionRequest = extractionReqQ)
        val (_, termsA) = manausTermsExtractionService
          .textTerms(indexName = indexName ,extractionRequest = extractionReqA)
        val scoredTermsUpdateReq = KBDocumentUpdate(questionScoredTerms = Some(termsQ.toList),
          answerScoredTerms = Some(termsA.toList))
        update(indexName = indexName, id = hit.document.id, document = scoredTermsUpdateReq, refresh = 0)
    }
  }

  def updateTextTermsFuture(indexName: String,
                            extractionRequest: UpdateQATermsRequest):
  Future[List[UpdateDocumentResult]] = Future {
    updateTextTerms(indexName, extractionRequest)
  }

  def updateAllTextTerms(indexName: String,
                         extractionRequest: UpdateQATermsRequest,
                         keepAlive: Long = 3600000): Iterator[UpdateDocumentResult] = {
    allDocuments(indexName = indexName, keepAlive = keepAlive).map { item =>
        val extractionReqQ = extractionReq(text = item.question, er = extractionRequest)
        val extractionReqA = extractionReq(text = item.answer, er = extractionRequest)
        val (_, termsQ) = manausTermsExtractionService
          .textTerms(indexName = indexName ,extractionRequest = extractionReqQ)
        val (_, termsA) = manausTermsExtractionService
          .textTerms(indexName = indexName ,extractionRequest = extractionReqA)
        val scoredTermsUpdateReq = KBDocumentUpdate(questionScoredTerms = Some(termsQ.toList),
          answerScoredTerms = Some(termsA.toList))
        update(indexName = indexName, id = item.id, document = scoredTermsUpdateReq, refresh = 0)
    }
  }
}
