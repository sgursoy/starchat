package com.getjenny.command

/**
  * Created by angelo on 29/03/17.
  */

import java.io._
import java.util.Base64

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.{HttpRequest, _}
import akka.http.scaladsl.model.headers.RawHeader
import akka.stream.ActorMaterializer
import breeze.io.CSVReader
import com.getjenny.starchat.entities._
import com.getjenny.starchat.serializers.JsonSupport
import com.roundeights.hasher.Implicits._
import scopt.OptionParser

import scala.collection.immutable
import scala.collection.immutable.{List, Map}
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import scala.util.Try

case class IndexKnowledgeBaseException(message: String = "", cause: Throwable = None.orNull)
  extends Exception(message, cause)

object IndexKnowledgeBase extends JsonSupport {
  private[this] case class Params(
                                   host: String = "http://localhost:8888",
                                   indexName: String = "index_english_0",
                                   path: String = "/knowledgebase",
                                   questionsPath: Option[String] = None: Option[String],
                                   answersPath: Option[String] = None: Option[String],
                                   associationsPath: Option[String] = None: Option[String],
                                   base64: Boolean = false,
                                   separator: Char = ';',
                                   timeout: Int = 60,
                                   headerKv: Seq[String] = Seq.empty[String]
                                 )

  private[this] def decodeBase64(in: String): String = {
    val decodedBytes = Base64.getDecoder.decode(in)
    val decoded = new String(decodedBytes, "UTF-8")
    decoded
  }

  private[this] def loadData(params: Params, transform: String => String):
  List[Map[String, String]] = {
    val questionsPath = params.questionsPath match {
      case Some(path) => path
      case _ => throw IndexKnowledgeBaseException("questions path canno be empty")
    }
    val questionsInputStream: Reader =
      new InputStreamReader(new FileInputStream(questionsPath), "UTF-8")
    lazy val questionsEntries = CSVReader.read(input = questionsInputStream, separator = params.separator,
      quote = '"', skipLines = 0)

    val questionsMap = questionsEntries.zipWithIndex.map { case (entry, index) =>
      if (entry.size < 2) {
        println("Error [questions] with line: " + index)
        (index, false, "", "")
      } else {
        val entry0: String = entry(0)
        val entry1: String = entry(1)
        (index, true, entry0, transform(entry1))
      }
    }.filter(_._2).map(x => (x._3, x._4)).toMap

    val answersInputStream: Reader = new InputStreamReader(new FileInputStream(params.answersPath.get), "UTF-8")
    lazy val answersEntries = CSVReader.read(input = answersInputStream, separator = params.separator,
      quote = '"', skipLines = 0)

    val answerMap = answersEntries.zipWithIndex.map{ case(entry, index) =>
      if (entry.size < 2) {
        println("Error [answers] with line: " + index)
        (index, false, "", "")
      } else {
        val entry0: String = entry(0)
        val entry1: String = entry(1)
        (index, true, entry0, transform(entry1))
      }
    }.filter(_._2).map(x => (x._3, x._4)).toMap

    val fileAssoc = new File(params.associationsPath.get)
    val fileReaderAssoc = new FileReader(fileAssoc)
    lazy val associationEntries = CSVReader.read(input = fileReaderAssoc, separator = params.separator,
      quote = '"', skipLines = 1)

    val convPairs = associationEntries.map(entry => {
      val question_id = entry(0)
      val answer_id = entry(3)
      val question = Try(questionsMap(question_id)).getOrElse("")
      val answer = Try(answerMap(answer_id)).getOrElse("")
      val val_map = Map(
        "conversation_id" -> entry(1),
        "question_id" -> question_id,
        "position" -> entry(2),
        "answer_id" -> answer_id,
        "question" -> question,
        "answer" -> answer
      )
      val_map
    })
    convPairs.toList
  }

  private[this] def execute(params: Params) {
    implicit val system: ActorSystem = ActorSystem()
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    implicit val executionContext: ExecutionContextExecutor = system.dispatcher

    val baseUrl = params.host + "/" + params.indexName + params.path

    val conversationItems = if (params.base64) {
      loadData(params, decodeBase64)
    } else {
      loadData(params, identity)
    }

    val httpHeader: immutable.Seq[HttpHeader] = if(params.headerKv.nonEmpty) {
      val headers: Seq[RawHeader] = params.headerKv.map(x => {
        val header_opt = x.split(":")
        val key = header_opt(0)
        val value = header_opt(1)
        RawHeader(key, value)
      }) ++ Seq(RawHeader("application", "json"))
      headers.to[immutable.Seq]
    } else {
      immutable.Seq(RawHeader("application", "json"))
    }

    val timeout = Duration(params.timeout, "s")

    conversationItems.foreach(entry => {
      val id: String = entry.toString().sha256

      val kbDocument: KBDocument = KBDocument(
        id = id,
        conversation = entry("conversation_id"),
        indexInConversation =  Option { entry("position").toInt },
        question = entry("question"),
        questionNegative = None: Option[List[String]],
        questionScoredTerms = None: Option[List[(String, Double)]],
        answer = entry("answer"),
        answerScoredTerms = None: Option[List[(String, Double)]],
        topics = None: Option[String],
        dclass = None: Option[String],
        doctype = doctypes.normal,
        state = None: Option[String],
      )

      val entity_future = Marshal(kbDocument).to[MessageEntity]
      val entity = Await.result(entity_future, 10.second)
      val responseFuture: Future[HttpResponse] =
        Http().singleRequest(HttpRequest(
          method = HttpMethods.POST,
          uri = baseUrl,
          headers = httpHeader,
          entity = entity))
      val result = Await.result(responseFuture, timeout)
      result.status match {
        case StatusCodes.Created | StatusCodes.OK => println("indexed: " + kbDocument.id +
          " conv(" + kbDocument.conversation + ")" +
          " position(" + kbDocument.indexInConversation.get + ")" +
          " q_id(" + entry("question_id") + ")" +
          " a_id(" + entry("answer_id") + ")")
        case _ =>
          println("failed indexing entry(" + entry + ") Message(" + result.toString() + ")")
      }
    })
    Await.ready(system.terminate(), Duration.Inf)
  }

  def main(args: Array[String]) {
    val defaultParams = Params()
    val parser = new OptionParser[Params]("IndexKnowledgeBase") {
      head("Index conversations into the KnowledgeBase")
      help("help").text("prints this usage text")
      opt[String]("questions_path")
        .text(s"path of the file with questions, format: <question_id>;<question>" +
          s"  default: ${defaultParams.questionsPath}")
        .action((x, c) => c.copy(questionsPath = Option(x)))
      opt[String]("answers_path")
        .text(s"path of the file with answers, format: <answer_id>;<answer>" +
          s"  default: ${defaultParams.answersPath}")
        .action((x, c) => c.copy(answersPath = Option(x)))
      opt[String]("associations_path")
        .text(s"path of the file with answers in format: " +
          "<question_id>;<conversation_id>;<pos. in conv.>;<answer_id>" +
          s"  default: ${defaultParams.associationsPath}")
        .action((x, c) => c.copy(associationsPath = Option(x)))
      opt[String]("host")
        .text(s"*Chat base url" +
          s"  default: ${defaultParams.host}")
        .action((x, c) => c.copy(host = x))
      opt[String]("index_name")
        .text(s"the index_name, e.g. index_XXX" +
          s"  default: ${defaultParams.indexName}")
        .action((x, c) => c.copy(indexName = x))
      opt[String]("path")
        .text(s"the service path" +
          s"  default: ${defaultParams.path}")
        .action((x, c) => c.copy(path = x))
      opt[Int]("timeout")
        .text(s"the timeout in seconds of each insert operation" +
          s"  default: ${defaultParams.timeout}")
        .action((x, c) => c.copy(timeout = x))
      opt[Boolean]("base64")
        .text(s"specify if questions and answer are encoded in base 64" +
          s"  default: ${defaultParams.base64}")
        .action((x, c) => c.copy(base64 = x))
      opt[Seq[String]]("header_kv")
        .text(s"header key-value pair, as key1:value1,key2:value2" +
          s"  default: ${defaultParams.headerKv}")
        .action((x, c) => c.copy(headerKv = x))
    }

    parser.parse(args, defaultParams) match {
      case Some(params) =>
        execute(params)
      case _ =>
        sys.exit(1)
    }
  }
}
