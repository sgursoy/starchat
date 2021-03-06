package com.getjenny.starchat.serializers

/**
  * Created by Angelo Leto <angelo@getjenny.com> on 27/06/16.
  */

import akka.http.scaladsl.common.{EntityStreamingSupport, JsonEntityStreamingSupport}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import com.getjenny.analyzer.expressions.AnalyzersData
import com.getjenny.starchat.entities._
import spray.json._
import akka.http.scaladsl.unmarshalling.Unmarshaller

import scalaz.Scalaz._

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  private[this] val start: ByteString = ByteString.empty
  private[this] val sep: ByteString = ByteString("\n")
  private[this] val end: ByteString = ByteString.empty

  implicit val jsonStreamingSupport: JsonEntityStreamingSupport =
    EntityStreamingSupport.json(1024 * 1024) // max object size 1MB
      .withFramingRendererFlow(Flow[ByteString].intersperse(start, sep, end).asJava)
      .withParallelMarshalling(parallelism = 8, unordered = true)

  implicit val SearchAlgorithmUnmarshalling:
    Unmarshaller[String, SearchAlgorithm.Value] =
    Unmarshaller.strict[String, SearchAlgorithm.Value] { enumValue =>
      SearchAlgorithm.value(enumValue)
    }

  implicit object SearchAlgorithmFormat extends JsonFormat[SearchAlgorithm.Value] {
    def write(obj: SearchAlgorithm.Value): JsValue = JsString(obj.toString)
    def read(json: JsValue): SearchAlgorithm.Value = json match {
      case JsString(str) =>
        SearchAlgorithm.values.find(_.toString === str) match {
          case Some(t) => t
          case _ => throw DeserializationException("SearchAlgorithm string is invalid")
        }
      case _ => throw DeserializationException("SearchAlgorithm string expected")
    }
  }

  implicit val responseMessageDataFormat = jsonFormat2(ReturnMessageData)
  implicit val responseRequestUserInputFormat = jsonFormat2(ResponseRequestInUserInput)
  implicit val responseRequestInputFormat = jsonFormat9(ResponseRequestIn)
  implicit val responseRequestOutputFormat = jsonFormat13(ResponseRequestOut)
  implicit val dtDocumentFormat = jsonFormat13(DTDocument)
  implicit val dtDocumentUpdateFormat = jsonFormat12(DTDocumentUpdate)
  implicit val kbDocumentFormat = jsonFormat14(KBDocument)
  implicit val kbDocumentUpdateFormat = jsonFormat13(KBDocumentUpdate)
  implicit val searchKBDocumentFormat = jsonFormat2(SearchKBDocument)
  implicit val searchDTDocumentFormat = jsonFormat2(SearchDTDocument)
  implicit val searchKBResultsFormat = jsonFormat3(SearchKBDocumentsResults)
  implicit val searchDTResultsFormat = jsonFormat3(SearchDTDocumentsResults)
  implicit val kbDocumentSearchFormat = jsonFormat16(KBDocumentSearch)
  implicit val dtDocumentSearchFormat = jsonFormat9(DTDocumentSearch)
  implicit val indexDocumentResultFormat = jsonFormat5(IndexDocumentResult)
  implicit val updateDocumentResultFormat = jsonFormat5(UpdateDocumentResult)
  implicit val deleteDocumentResultFormat = jsonFormat5(DeleteDocumentResult)
  implicit val indexDocumentResultListFormat = jsonFormat1(IndexDocumentListResult)
  implicit val updateDocumentResultListFormat = jsonFormat1(UpdateDocumentListResult)
  implicit val deleteDocumentResultListFormat = jsonFormat1(DeleteDocumentsResult)
  implicit val deleteDocumentsResultFormat = jsonFormat2(DeleteDocumentsSummaryResult)
  implicit val listOfDocumentIdFormat = jsonFormat1(ListOfDocumentId)
  implicit val dtAnalyzerItemFormat = jsonFormat4(DTAnalyzerItem)
  implicit val dtAnalyzerMapFormat = jsonFormat1(DTAnalyzerMap)
  implicit val dtAnalyzerLoadFormat = jsonFormat1(DTAnalyzerLoad)
  implicit val indexManagementResponseFormat = jsonFormat1(IndexManagementResponse)
  implicit val languageGuesserRequestInFormat = jsonFormat1(LanguageGuesserRequestIn)
  implicit val languageGuesserRequestOutFormat = jsonFormat4(LanguageGuesserRequestOut)
  implicit val languageGuesserInformationsFormat = jsonFormat1(LanguageGuesserInformations)
  implicit val searchTermFormat = jsonFormat9(SearchTerm)
  implicit val termFormat = jsonFormat9(Term)
  implicit val termIdsRequestFormat = jsonFormat1(DocsIds)
  implicit val termsFormat = jsonFormat1(Terms)
  implicit val termsResultsFormat = jsonFormat3(TermsResults)
  implicit val textTermsFormat = jsonFormat4(TextTerms)
  implicit val failedShardsFormat = jsonFormat4(FailedShard)
  implicit val refreshIndexResultFormat = jsonFormat5(RefreshIndexResult)
  implicit val refreshIndexResultsFormat = jsonFormat1(RefreshIndexResults)
  implicit val analyzerQueryRequestFormat = jsonFormat2(TokenizerQueryRequest)
  implicit val analyzerResponseItemFormat = jsonFormat5(TokenizerResponseItem)
  implicit val analyzerResponseFormat = jsonFormat1(TokenizerResponse)
  implicit val analyzerDataFormat = jsonFormat2(AnalyzersData)
  implicit val analyzerEvaluateRequestFormat = jsonFormat5(AnalyzerEvaluateRequest)
  implicit val analyzerEvaluateResponseFormat = jsonFormat4(AnalyzerEvaluateResponse)
  implicit val spellcheckTokenSuggestionsFormat = jsonFormat3(SpellcheckTokenSuggestions)
  implicit val spellcheckTokenFormat = jsonFormat4(SpellcheckToken)
  implicit val spellcheckTermsResponseFormat = jsonFormat1(SpellcheckTermsResponse)
  implicit val spellcheckTermsRequestFormat = jsonFormat4(SpellcheckTermsRequest)
  implicit val responseRequestOutOperationResultFormat = jsonFormat2(ResponseRequestOutOperationResult)

  implicit object PermissionsJsonFormat extends JsonFormat[Permissions.Value] {
    def write(obj: Permissions.Value): JsValue = JsString(obj.toString)
    def read(json: JsValue): Permissions.Value = json match {
      case JsString(str) =>
        Permissions.values.find(_.toString === str) match {
          case Some(t) => t
          case _ => throw DeserializationException("Permission string is invalid")
        }
      case _ => throw DeserializationException("Permission string expected")
    }
  }

  implicit val userFormat = jsonFormat4(User)
  implicit val userUpdateFormat = jsonFormat4(UserUpdate)
  implicit val userDelete = jsonFormat1(UserId)
  implicit val dtReloadTimestamp = jsonFormat2(DtReloadTimestamp)
  implicit val openCloseIndexFormat = jsonFormat5(OpenCloseIndex)

  implicit val commonOrSpecificSearchUnmarshalling:
    Unmarshaller[String, CommonOrSpecificSearch.Value] =
    Unmarshaller.strict[String, CommonOrSpecificSearch.Value] { enumValue =>
      CommonOrSpecificSearch.value(enumValue)
    }

  implicit object CommonOrSpecificSearchFormat extends JsonFormat[CommonOrSpecificSearch.Value] {
    def write(obj: CommonOrSpecificSearch.Value): JsValue = JsString(obj.toString)
    def read(json: JsValue): CommonOrSpecificSearch.Value = json match {
      case JsString(str) =>
        CommonOrSpecificSearch.values.find(_.toString === str) match {
          case Some(t) => t
          case _ => throw DeserializationException("CommonOrSpecificSearch string is invalid")
        }
      case _ => throw DeserializationException("CommonOrSpecificSearch string expected")
    }
  }

  implicit val observedDataSourcesUnmarshalling:
    Unmarshaller[String, ObservedDataSources.Value] = Unmarshaller.strict[String, ObservedDataSources.Value] { enumValue =>
    ObservedDataSources.value(enumValue)
  }

  implicit object ObservedSearchDestFormat extends JsonFormat[ObservedDataSources.Value] {
    def write(obj: ObservedDataSources.Value): JsValue = JsString(obj.toString)
    def read(json: JsValue): ObservedDataSources.Value = json match {
      case JsString(str) =>
        ObservedDataSources.values.find(_.toString === str) match {
          case Some(t) => t
          case _ => throw DeserializationException("ObservedSearchDests string is invalid")
        }
      case _ => throw DeserializationException("ObservedSearchDests string expected")
    }
  }

  implicit val termCountFieldsUnmarshalling:
    Unmarshaller[String, TermCountFields.Value] =
    Unmarshaller.strict[String, TermCountFields.Value] { enumValue =>
      TermCountFields.value(enumValue)
    }

  implicit object TermCountFieldsFormat extends JsonFormat[TermCountFields.Value] {
    def write(obj: TermCountFields.Value): JsValue = JsString(obj.toString)
    def read(json: JsValue): TermCountFields.Value = json match {
      case JsString(str) =>
        TermCountFields.values.find(_.toString === str) match {
          case Some(t) => t
          case _ => throw DeserializationException("TermCountFields string is invalid")
        }
      case _ => throw DeserializationException("TermCountFields string expected")
    }
  }

  implicit val synonymExtractionDistanceFunctionUnmarshalling:
    Unmarshaller[String, SynonymExtractionDistanceFunction.Value] =
    Unmarshaller.strict[String, SynonymExtractionDistanceFunction.Value] { enumValue =>
      SynonymExtractionDistanceFunction.value(enumValue)
    }

  implicit object SynonymExtractionDistanceFunctionFormat extends JsonFormat[SynonymExtractionDistanceFunction.Value] {
    def write(obj: SynonymExtractionDistanceFunction.Value): JsValue = JsString(obj.toString)
    def read(json: JsValue): SynonymExtractionDistanceFunction.Value = json match {
      case JsString(str) =>
        SynonymExtractionDistanceFunction.values.find(_.toString === str) match {
          case Some(t) => t
          case _ => throw DeserializationException("SynonymExtractionDistanceFunction string is invalid")
        }
      case _ => throw DeserializationException("SynonymExtractionDistanceFunction string expected")
    }
  }

  implicit val termCountFormat = jsonFormat2(TermCount)
  implicit val totalTermsFormat = jsonFormat3(TotalTerms)
  implicit val dictSizeFormat = jsonFormat4(DictSize)
  implicit val termsExtractionRequestFormat = jsonFormat15(TermsExtractionRequest)
  implicit val synExtractionRequestFormat = jsonFormat19(SynExtractionRequest)
  implicit val synonymItemFormat = jsonFormat4(SynonymItem)
  implicit val synonymExtractionItemFormat = jsonFormat4(SynonymExtractionItem)
  implicit val tokenFrequencyItemFormat = jsonFormat3(TokenFrequencyItem)
  implicit val tokenFrequencyFormat = jsonFormat3(TokenFrequency)
  implicit val termsDistanceResFormat = jsonFormat6(TermsDistanceRes)
  implicit val updateQATermsRequestFormat = jsonFormat13(UpdateQATermsRequest)
  implicit val countersCacheParametersFormat = jsonFormat4(CountersCacheParameters)
  implicit val countersCacheSizeFormat = jsonFormat3(CountersCacheSize)
}
