package edu.twitter.model.evaluation

import com.typesafe.scalalogging.Logger
import edu.twitter.config.AppConfig
import edu.twitter.model.client.classification.ClassificationClient
import edu.twitter.model.client.dto.Label
import edu.twitter.model.impl.TweetsLoader
import edu.twitter.service.SpellingCorrectionService
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.Row
import org.elasticsearch.spark.rdd.EsSpark

/** Grouping of the evaluation necessary fields
  * these fields are used for evaluating the training error. */
case class EvaluationFields(happyCorrect: Int, happyTotal: Int, sadCorrect: Int, sadTotal: Int) {

  /**
    * Combining two evaluation instances.
    *
    * @param o instance to combine with this one.
    * @return a new instance resulting of the combination of
    *         both instances.
    */
  def +(o: EvaluationFields): EvaluationFields =
    EvaluationFields(
      happyCorrect + o.happyCorrect,
      happyTotal + o.happyTotal,
      sadCorrect + o.sadCorrect,
      sadTotal + o.sadTotal
    )
}

/** Representation of a training tweet that has been classified
  * by the model, it holds both the actual and model labels. */
case class EvaluatedTrainingTweet(actualLabel: Label, modelPrediction: Label, tweetText: String)

/** Representation of a training tweet that has been classified
  * by the model, it holds both the actual and model labels.
  * used to be saved in elasticsearch. */
case class EvaluatedTrainingTweetElasticRepresentation(actualLabel: Double, modelPrediction: Double, tweetText: String)

/** Representation of the evaluation results based on
  * spelling correction. */
case class EvaluationWithCorrection(actualLabel: Double, beforeLabel: Double,
                                    afterLabel: Double, beforeTweet: String, afterTweet: String)


/** Responsible for evaluating a model based on a given
  * testing data. this class will print training analysis
  * in the console and persist the labeled data in `Elasticsearch`
  * for further visualization.
  *
  * @param sc spark context.
  */
class ModelEvaluator(sc: SparkContext)(implicit appConfig: AppConfig) {
  private val logger = Logger(classOf[ModelEvaluator])

  /**
    * Evaluate the given Seq of models.
    *
    * @param models target models for evaluation
    */
  def evaluate(models: Seq[String]): Unit = {
    models.foreach(evaluate)
  }

  /**
    * Evaluate the given Seq of models with
    * and without correction.
    *
    * @param models target models for evaluation
    */
  def evaluateWithCorrection(models: Seq[String]): Unit = {
    models.foreach(evaluateWithCorrection)
  }

  /**
    * Show how the model will perform against a
    * prelabeled data.
    *
    * @param modelName name of the target model for evaluation.
    */
  def evaluate(modelName: String): Unit = {
    val tweetsLoader = new TweetsLoader(sc)
    val evaluation = evaluateData(modelName, tweetsLoader.loadDataSet(appConfig.paths.validationDataPath))
    performEvaluationAnalysis(evaluation)
    if (appConfig.persistEvaluation) {
      val elasticEvaluatedData = for {
        evaluatedTweet <- evaluation
        actualLabel = evaluatedTweet.actualLabel.getKibanaRepresentation
        modelPredictedLabel = evaluatedTweet.modelPrediction.getKibanaRepresentation
      } yield EvaluatedTrainingTweetElasticRepresentation(actualLabel, modelPredictedLabel, evaluatedTweet.tweetText)
      EsSpark.saveToEs(elasticEvaluatedData, s"${modelName.toLowerCase}/performance")
    }
  }

  /**
    * Show the effect of spelling correction over the model's
    * performance.
    *
    * @param modelName name of the target model for evaluation
    */
  def evaluateWithCorrection(modelName: String): Unit = {
    val tweetsLoader = new TweetsLoader(sc)
    val evaluation = evaluateDataWithCorrection(modelName, tweetsLoader.loadDataSet(appConfig.paths.validationDataPath))
    EsSpark.saveToEs(evaluation, s"${modelName.toLowerCase}/correction")
  }

  /**
    * Evaluate the model before and after spelling correction.
    *
    * @param modelName name of target model for evaluation
    * @param data      evaluation data
    * @return model evaluation
    */
  private def evaluateDataWithCorrection(modelName: String, data: RDD[Row]): RDD[EvaluationWithCorrection] = {
    val labelMapping = Map(0.0 -> Label.SAD, 1.0 -> Label.HAPPY)
    val modelPort = appConfig.modelServicePorts(modelName)
    val evaluation = for {
      row <- data
      actualLabel = labelMapping(row.getAs[Double]("label")).getKibanaRepresentation
      tweetText = row.getAs[String]("msg")
      beforeCorrection <- ClassificationClient.callModelService(modelPort, modelName, tweetText)
      correctTweet = SpellingCorrectionService.correctSpelling(tweetText)
      afterCorrection <- ClassificationClient.callModelService(modelPort, modelName, correctTweet)
      beforeLabel = beforeCorrection.getKibanaRepresentation
      afterLabel = afterCorrection.getKibanaRepresentation
    } yield EvaluationWithCorrection(actualLabel, beforeLabel, afterLabel, tweetText, correctTweet)

    evaluation
  }

  /**
    * Show how the model will perform against the given data.
    *
    * @param modelName name of the target model for evaluation.
    * @param data      evaluation data
    * @return rdd of `EvaluatedTrainingTweet`
    */
  private def evaluateData(modelName: String, data: RDD[Row]): RDD[EvaluatedTrainingTweet] = {
    val labelMapping = Map(0.0 -> Label.SAD, 1.0 -> Label.HAPPY)
    val modelPort = appConfig.modelServicePorts(modelName)
    val evaluation = for {
      row <- data
      actualLabel = labelMapping(row.getAs[Double]("label"))
      tweetText = row.getAs[String]("msg")
      callRes <- ClassificationClient.callModelService(modelPort, modelName, tweetText)
    } yield EvaluatedTrainingTweet(actualLabel, callRes, tweetText)

    evaluation
  }

  /**
    * Perform basic analysis over the model's classification.
    *
    * @param evaluation classified tweets
    */
  private def performEvaluationAnalysis(evaluation: RDD[EvaluatedTrainingTweet], dataSetType: String = "Testing"): Unit = {
    val e = evaluation.aggregate(EvaluationFields(0, 0, 0, 0))(
      (e, t) => (t.actualLabel, t.modelPrediction) match {
        case (Label.HAPPY, Label.HAPPY) => e.copy(happyCorrect = e.happyCorrect + 1, happyTotal = e.happyTotal + 1)
        case (Label.HAPPY, Label.SAD) => e.copy(happyTotal = e.happyTotal + 1)
        case (Label.SAD, Label.HAPPY) => e.copy(sadTotal = e.sadTotal + 1)
        case (Label.SAD, Label.SAD) => e.copy(sadCorrect = e.sadCorrect + 1, sadTotal = e.sadTotal + 1)
      },
      (e1, e2) => e1 + e2
    )

    logger.info(s"=============== $dataSetType Evaluation ==================")
    logger.info(s"sad messages=${e.sadTotal}, happy messages=${e.happyTotal}")
    logger.info(s"happy % correct=${e.happyCorrect.toDouble / e.happyTotal}")
    logger.info(s"sad % correct=${e.sadCorrect.toDouble / e.sadTotal}")

    val recordCount = evaluation.count()
    val testErr = evaluation.filter(t => t.actualLabel != t.modelPrediction).count.toDouble / recordCount
    logger.info(s"data size=$recordCount")
    logger.info(s"Test Error=$testErr")
  }
}
