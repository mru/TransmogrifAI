/*
 * Copyright (c) 2017, Salesforce.com, Inc.
 * All rights reserved.
 */

package com.salesforce.op.stages.impl.classification

import com.salesforce.op.evaluators._
import com.salesforce.op.features.types._
import com.salesforce.op.features.Feature
import com.salesforce.op.stages.impl.classification.ClassificationModelsToTry._
import com.salesforce.op.stages.impl.classification.ProbabilisticClassifierType.ProbClassifier
import com.salesforce.op.stages.impl.selector.ModelSelectorBaseNames
import com.salesforce.op.stages.impl.tuning._
import com.salesforce.op.stages.sparkwrappers.generic.{SwQuaternaryTransformer, SwTernaryTransformer}
import com.salesforce.op.test.{TestFeatureBuilder, TestSparkContext}
import com.salesforce.op.utils.spark.RichDataset._
import com.salesforce.op.utils.spark.RichMetadata._
import org.apache.spark.ml.classification.{DecisionTreeClassifier, RandomForestClassifier}
import org.apache.spark.ml.linalg.{Vector, Vectors}
import org.apache.spark.ml.tuning.ParamGridBuilder
import org.apache.spark.mllib.random.RandomRDDs._
import org.apache.spark.sql.Encoders
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.functions._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{Assertions, FlatSpec, Matchers}
import FunctionalityForClassificationTests._

@RunWith(classOf[JUnitRunner])
class BinaryClassificationModelSelectorTest extends FlatSpec with TestSparkContext {
  val (seed, smallCount, bigCount) = (1234L, 20, 80)

  import spark.implicits._

  // Generate positive observations following a distribution ~ N((0.0, 0.0, 0.0), I_3)
  val positiveData =
    normalVectorRDD(spark.sparkContext, bigCount, 3, seed = seed)
      .map(v => 1.0 -> Vectors.dense(v.toArray))

  // Generate negative observations following a distribution ~ N((10.0, 10.0, 10.0), I_3)
  val negativeData =
    normalVectorRDD(spark.sparkContext, smallCount, 3, seed = seed)
      .map(v => 0.0 -> Vectors.dense(v.toArray.map(_ + 10.0)))

  val stageNames = Array("label_prediction", "label_rawPrediction", "label_probability")

  val data = positiveData.union(negativeData).toDF("label", "features")

  val Array(rawLabel: Feature[RealNN]@unchecked, features: Feature[OPVector]@unchecked) =
    TestFeatureBuilder(data, nonNullable = data.schema.fields.map(_.name).toSet)

  val label = rawLabel.copy(isResponse = true)

  val modelSelector = BinaryClassificationModelSelector().setInput(label, features)
  Spec[BinaryClassificationModelSelector] should "have properly formed stage1" in {
    modelSelector.stage1 shouldBe a[Stage1BinaryClassificationModelSelector]

    val inputNames = modelSelector.stage1.getInputFeatures().map(_.name)
    inputNames should have length 2
    inputNames shouldBe Array(label.name, features.name)
    modelSelector.stage1.getOutput().name shouldBe modelSelector.stage1.outputName
    the[RuntimeException] thrownBy {
      modelSelector.setInput(label.copy(isResponse = true), features.copy(isResponse = true))
    } should have message "The feature vector should not contain any response features."
  }

  it should "have properly formed stage2" in {
    modelSelector.stage2 shouldBe a[SwTernaryTransformer[_, _, _, _, _]]

    val inputNames = modelSelector.stage2.getInputFeatures().map(_.name)
    inputNames should have length 3
    inputNames shouldBe Array(label.name, features.name, modelSelector.stage1.outputName)
    modelSelector.stage2.getOutput().name shouldBe modelSelector.stage2.outputName

  }

  it should "have properly formed stage3" in {
    modelSelector.stage3 shouldBe a[SwQuaternaryTransformer[_, _, _, _, _, _]]

    val inputNames = modelSelector.stage3.getInputFeatures().map(_.name)
    inputNames should have length 4
    inputNames shouldBe Array(label.name, features.name, modelSelector.stage1.outputName,
      modelSelector.stage2.outputName
    )
    modelSelector.stage3.getOutput().name shouldBe modelSelector.stage3.outputName
  }

  it should "have proper outputs corresponding to the stages" in {
    val outputs = modelSelector.getOutput()
    outputs._1.name shouldBe modelSelector.stage1.getOutput().name
    outputs._2.name shouldBe modelSelector.stage2.getOutput().name
    outputs._3.name shouldBe modelSelector.stage3.getOutput().name

    // as long as the parent stages are correct, we can also assume
    // that the parent features are correct, since that should
    // be verified in the unit tests for the transformers.
    outputs._1.originStage shouldBe modelSelector.stage1
    outputs._2.originStage shouldBe modelSelector.stage2
    outputs._3.originStage shouldBe modelSelector.stage3
  }

  it should "properly select models to try" in {
    modelSelector.setModelsToTry(LogisticRegression, RandomForest)

    modelSelector.stage1.get(modelSelector.stage1.useLR).get shouldBe true
    modelSelector.stage1.get(modelSelector.stage1.useRF).get shouldBe true
    modelSelector.stage1.get(modelSelector.stage1.useDT).get shouldBe false
    modelSelector.stage1.get(modelSelector.stage1.useNB).get shouldBe false
  }

  it should "set the Logistic Regression Params properly" in {
    modelSelector.setLogisticRegressionElasticNetParam(0.1)
      .setLogisticRegressionFitIntercept(true, false)
      .setLogisticRegressionMaxIter(42)
      .setLogisticRegressionRegParam(0.1, 0.01)
      .setLogisticRegressionStandardization(true)
      .setLogisticRegressionTol(0.005, 0.00002)
      .setLogisticRegressionThreshold(0.8)

    modelSelector.stage1.sparkLR.getElasticNetParam shouldBe 0.1
    modelSelector.stage1.sparkLR.getMaxIter shouldBe 42
    modelSelector.stage1.sparkLR.getStandardization shouldBe true
    modelSelector.stage1.sparkLR.getThreshold shouldBe 0.8

    val lrGrid = new ParamGridBuilder().addGrid(modelSelector.stage1.sparkLR.fitIntercept, Array(true, false))
      .addGrid(modelSelector.stage1.sparkLR.regParam, Array(0.1, 0.01))
      .addGrid(modelSelector.stage1.sparkLR.tol, Array(0.005, 0.00002))
      .build

    (modelSelector.stage1.lRGrid.build().toSeq zip lrGrid.toSeq)
      .map { case (grid1, grid2) => grid1.toSeq shouldBe grid2.toSeq }
  }

  it should "set the Random Forest Params properly" in {
    modelSelector.setRandomForestImpurity(Impurity.Entropy, Impurity.Gini)
      .setRandomForestMaxBins(34)
      .setRandomForestMaxDepth(7, 8)
      .setRandomForestMinInfoGain(0.1)
      .setRandomForestMinInstancesPerNode(2, 3, 4)
      .setRandomForestSeed(34L)
      .setRandomForestSubsamplingRate(0.4, 0.8)
      .setRandomForestNumTrees(10)

    val sparkRandomForest = modelSelector.stage1.sparkRF.asInstanceOf[RandomForestClassifier]

    sparkRandomForest.getMaxBins shouldBe 34
    sparkRandomForest.getMinInfoGain shouldBe 0.1
    sparkRandomForest.getSeed shouldBe 34L
    sparkRandomForest.getNumTrees shouldBe 10

    val rfGrid =
      new ParamGridBuilder().addGrid(sparkRandomForest.impurity,
        Seq(Impurity.Entropy, Impurity.Gini).map(_.sparkName))
        .addGrid(sparkRandomForest.maxDepth, Array(7, 8))
        .addGrid(sparkRandomForest.minInstancesPerNode, Array(2, 3, 4))
        .addGrid(sparkRandomForest.subsamplingRate, Array(0.4, 0.8))
        .build

    (modelSelector.stage1.rFGrid.build().toSeq zip rfGrid.toSeq)
      .map { case (grid1, grid2) => grid1.toSeq shouldBe grid2.toSeq }
  }

  it should "set the Decision Tree Params properly" in {
    modelSelector.setDecisionTreeImpurity(Impurity.Entropy)
      .setDecisionTreeMaxBins(34, 44)
      .setDecisionTreeMaxDepth(10)
      .setDecisionTreeMinInfoGain(0.2, 0.5)
      .setDecisionTreeMinInstancesPerNode(5)
      .setDecisionTreeSeed(34L, 56L)

    val sparkDecisionTree = modelSelector.stage1.sparkDT.asInstanceOf[DecisionTreeClassifier]
    sparkDecisionTree.getImpurity shouldBe Impurity.Entropy.sparkName
    sparkDecisionTree.getMaxDepth shouldBe 10
    sparkDecisionTree.getMinInstancesPerNode shouldBe 5

    val dtGrid =
      new ParamGridBuilder()
        .addGrid(sparkDecisionTree.maxBins, Array(34, 44))
        .addGrid(sparkDecisionTree.minInfoGain, Array(0.2, 0.5))
        .addGrid(sparkDecisionTree.seed, Array(34L, 56L))
        .build

    (modelSelector.stage1.dTGrid.build().toSeq zip dtGrid.toSeq)
      .map { case (grid1, grid2) => grid1.toSeq shouldBe grid2.toSeq }
  }

  it should "set the Naive Bayes Params properly" in {
    modelSelector.setNaiveBayesModelType(ModelType.Multinomial, ModelType.Bernoulli)
    modelSelector.setNaiveBayesSmoothing(1.5)

    modelSelector.stage1.sparkNB.getSmoothing shouldBe 1.5

    val nbGrid =
      new ParamGridBuilder().addGrid(
        modelSelector.stage1.sparkNB.modelType,
        Array(ModelType.Multinomial, ModelType.Bernoulli).map(_.sparkName))
        .build

    (modelSelector.stage1.nBGrid.build().toSeq zip nbGrid.toSeq)
      .map { case (grid1, grid2) => grid1.toSeq shouldBe grid2.toSeq }
  }

  it should "set the thresholds correctly" in {
    modelSelector.setModelThresholds(Array(1.0, 2.0))

    modelSelector.stage1.sparkLR.getThresholds shouldBe Array(1.0, 2.0)
    modelSelector.stage1.sparkRF.asInstanceOf[RandomForestClassifier].getThresholds shouldBe Array(1.0, 2.0)
    modelSelector.stage1.sparkDT.asInstanceOf[DecisionTreeClassifier].getThresholds shouldBe Array(1.0, 2.0)
    modelSelector.stage1.sparkNB.getThresholds shouldBe Array(1.0, 2.0)

    modelSelector.stage1.getThresholds shouldBe Array(1.0, 2.0)
  }

  it should "set the cross validation params correctly" in {
    val crossValidator = new OpCrossValidation[ProbClassifier](
      numFolds = 3,
      evaluator = Evaluators.BinaryClassification.auPR(),
      seed = 10L
    )

    crossValidator.validationParams shouldBe Map(
      "metric" -> OpMetricsNames.auPR,
      "numFolds" -> 3,
      "seed" -> 10L
    )
  }

  it should "set the train validation split params correctly" in {
    val trainValidator = new OpTrainValidationSplit[ProbClassifier](
      trainRatio = 0.6,
      evaluator = Evaluators.BinaryClassification.auROC(),
      seed = 11L
    )

    trainValidator.validationParams shouldBe Map(
      "metric" -> OpMetricsNames.auROC,
      "trainRatio" -> 0.6,
      "seed" -> 11L
    )
  }

  it should "split into training and test even if the balancing is not desired" in {
    implicit val vectorEncoder: org.apache.spark.sql.Encoder[Vector] = ExpressionEncoder()
    implicit val e1 = Encoders.tuple(Encoders.scalaDouble, vectorEncoder)

    val testFraction = 0.2

    val splitData = DataSplitter(reserveTestFraction = testFraction)
      .split(data.withColumn(ModelSelectorBaseNames.idColName, monotonically_increasing_id())
        .as[(Double, Vector, Double)])

    val trainCount = splitData.train.count()
    val testCount = splitData.test.count()
    val totalCount = smallCount + bigCount

    assert(math.abs(testCount - testFraction * totalCount) <= 20)
    assert(math.abs(trainCount - (1.0 - testFraction) * totalCount) <= 20)

    trainCount + testCount shouldBe totalCount
  }

  it should "fit and predict" in {

    val testEstimator =
      BinaryClassificationModelSelector
        .withCrossValidation(
          Option(DataBalancer(sampleFraction = 0.5, seed = 11L)),
          numFolds = 4,
          validationMetric = Evaluators.BinaryClassification.precision(),
          seed = 10L
        )
        .setModelsToTry(LogisticRegression, RandomForest)
        .setLogisticRegressionRegParam(0.1)
        .setLogisticRegressionMaxIter(10, 100)
        .setRandomForestImpurity(Impurity.Entropy)
        .setRandomForestMaxDepth(2, 10)
        .setRandomForestNumTrees(10)
        .setInput(label, features)

    val model = testEstimator.fit(data)

    println(model.getMetadata())

    // evaluation metrics from test set should be in metadata
    val metaData = model.getMetadata().getSummaryMetadata().getMetadata(ModelSelectorBaseNames.HoldOutEval)

    // No evaluator passed so only the default one is there
    val evaluatorMetricName = testEstimator.trainTestEvaluators.head.name

    BinaryClassEvalMetrics.values.foreach(metric =>
      assert(metaData.contains(s"(${OpEvaluatorNames.binary})_${metric.entryName}"),
        s"Metric ${metric.entryName} is not present in metadata: " + metaData.json)
    )
    val transformedData = model.transform(data)
    val pred = model.getOutput()._1
    val justScores = transformedData.collect(pred)
    justScores shouldEqual data.collect(label)
  }

  it should "fit and predict even if there is no balancing" in {

    val testEstimator =
      BinaryClassificationModelSelector
        .withCrossValidation(
          Option(DataSplitter(reserveTestFraction = 0.2)),
          numFolds = 4,
          validationMetric = Evaluators.BinaryClassification.recall(),
          seed = 10L
        )
        .setModelsToTry(LogisticRegression, RandomForest)
        .setLogisticRegressionRegParam(0.1)
        .setLogisticRegressionMaxIter(10, 100)
        .setRandomForestImpurity(Impurity.Entropy)
        .setRandomForestMaxDepth(2, 10)
        .setRandomForestNumTrees(10)
        .setInput(label, features)

    val model = testEstimator.fit(data)

    println(model.getMetadata())

    // evaluation metrics from test set should be in metadata
    val metaData = model.getMetadata().getSummaryMetadata().getMetadata(ModelSelectorBaseNames.HoldOutEval)

    // No evaluator passed so only the default one is there
    val evaluatorMetricName = testEstimator.trainTestEvaluators.head.name

    BinaryClassEvalMetrics.values.foreach(metric =>
      assert(metaData.contains(s"(${OpEvaluatorNames.binary})_${metric.entryName}"),
        s"Metric ${metric.entryName} is not present in metadata: " + metaData.json)
    )
    val transformedData = model.transform(data)
    val pred = model.getOutput()._1
    val justScores = transformedData.collect(pred)
    justScores shouldEqual data.collect(label)
  }

  it should "fit and predict with a train validation split, " +
    "even if there is no split between training and test + custom evaluator" in {

    val crossEntropy = Evaluators.BinaryClassification.custom(
      metricName = "cross entropy",
      isLargerBetter = false,
      evaluateFn = crossEntropyFun
    )

    val testEstimator =
      BinaryClassificationModelSelector
        .withTrainValidationSplit(Option(DataBalancer(sampleFraction = 0.2, seed = 11L, splitData = false)),
          trainRatio = 0.8, validationMetric = crossEntropy, seed = 10L)
        .setModelsToTry(DecisionTree, RandomForest)
        .setRandomForestImpurity(Impurity.Gini)
        .setDecisionTreeMaxBins(64, 34)
        .setInput(label, features)

    val model = testEstimator.fit(data)

    println(model.getMetadata())

    val transformedData = model.transform(data)
    val pred = testEstimator.getOutput()._1
    val justScores = transformedData.collect(pred)
    justScores shouldEqual transformedData.collect(label)
  }

  it should "fit and predict with a train validation split, even if there is no split and balancing" in {

    val testEstimator =
      BinaryClassificationModelSelector
        .withTrainValidationSplit(None,
          trainRatio = 0.8, validationMetric = Evaluators.BinaryClassification.auROC(), seed = 10L)
        .setModelsToTry(DecisionTree, RandomForest)
        .setRandomForestImpurity(Impurity.Gini)
        .setDecisionTreeMaxBins(64, 34)
        .setInput(label, features)

    val model = testEstimator.fit(data)

    println(model.getMetadata())

    val transformedData = model.transform(data)
    val pred = testEstimator.getOutput()._1
    val justScores = transformedData.collect(pred)

    justScores shouldEqual transformedData.collect(label)
  }

  it should "fit and predict with a cross validation and compute correct metrics from trainTestEvaluators" in {

    val crossEntropy = Evaluators.BinaryClassification.custom(
      metricName = "cross entropy",
      isLargerBetter = false,
      evaluateFn = crossEntropyFun
    )

    val testEstimator =
      BinaryClassificationModelSelector
        .withCrossValidation(
          Option(DataSplitter(reserveTestFraction = 0.2)),
          numFolds = 4,
          validationMetric = Evaluators.BinaryClassification.recall(),
          trainTestEvaluators = Seq(crossEntropy),
          seed = 10L
        )
        .setModelsToTry(DecisionTree, RandomForest)
        .setRandomForestImpurity(Impurity.Gini)
        .setDecisionTreeMaxBins(64, 34)
        .setInput(label, features)

    val model = testEstimator.fit(data)

    // checking the holdOut Evaluators
    assert(testEstimator.trainTestEvaluators.contains(crossEntropy), "Cross entropy evaluator not present in estimator")

    // checking trainingEval & holdOutEval metrics
    val metaData = model.getMetadata().getSummaryMetadata()
    val trainMetaData = metaData.getMetadata(ModelSelectorBaseNames.TrainingEval)
    val holdOutMetaData = metaData.getMetadata(ModelSelectorBaseNames.HoldOutEval)

    testEstimator.trainTestEvaluators.foreach {
      case evaluator: OpBinaryClassificationEvaluator => {
        MultiClassEvalMetrics.values.foreach(metric =>
          Seq(trainMetaData, holdOutMetaData).foreach(
            metadata => assert(metadata.contains(s"(${OpEvaluatorNames.binary})_${metric.entryName}"),
              s"Metric ${metric.entryName} is not present in metadata: " + metadata.json)
          )
        )
      }
      case evaluator: OpBinaryClassificationEvaluatorBase[_] => {
        Seq(trainMetaData, holdOutMetaData).foreach(metadata =>
          assert(metadata.contains(s"(${evaluator.name})_${evaluator.name}"),
            s"Single Metric evaluator ${evaluator.name} is not present in metadata: " + metadata.json)
        )
      }
    }
  }

}