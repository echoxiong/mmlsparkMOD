// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark

import java.net.URI

import com.microsoft.ml.spark.FileUtilities.File
import com.microsoft.ml.spark.Readers.implicits._
import com.microsoft.ml.spark.schema.ImageSchema
import org.apache.commons.io.FileUtils
import org.apache.spark.ml.PipelineStage
import org.apache.spark.ml.linalg.DenseVector
import org.apache.spark.ml.util.{MLReadable, MLWritable}
import org.apache.spark.sql.DataFrame
import org.apache.spark.image.ImageFileFormat
import org.apache.spark.sql.types.StringType
import org.apache.spark.sql.functions.{col, udf}

class ImageFeaturizerSuite extends CNTKTestUtils with FileReaderUtils
  with TransformerFuzzing[ImageFeaturizer]{

  val images: DataFrame = session.readImages(imagePath, true).withColumnRenamed("image", inputCol)

  val modelDir = new File(filesRoot, "CNTKModel")
  val modelDownloader = new ModelDownloader(session, modelDir.toURI)

  lazy val resNetUri: URI = new File(modelDir, "ResNet50_ImageNet.model").toURI
  lazy val resNet: ModelSchema = modelDownloader.downloadByName("ResNet50")

  test("Image featurizer should reproduce the CIFAR10 experiment") {
    val model = new ImageFeaturizer()
      .setInputCol(inputCol)
      .setOutputCol(outputCol)
      .setModelLocation(s"${sys.env("DATASETS_HOME")}/CNTKModel/ConvNet_CIFAR10.model")
      .setCutOutputLayers(0)
      .setLayerNames(Array("z"))
    val result = model.transform(images)
    compareToTestModel(result)
  }

  test("structured streaming") {

    val model = new ImageFeaturizer()
      .setInputCol("image")
      .setOutputCol(outputCol)
      .setModelLocation(s"${sys.env("DATASETS_HOME")}/CNTKModel/ConvNet_CIFAR10.model")
      .setCutOutputLayers(0)
      .setLayerNames(Array("z"))

    val imageDF = session
      .readStream
      .format(classOf[ImageFileFormat].getName)
      .schema(ImageSchema.schema)
      .load(cifarDirectory)

    val resultDF = model.transform(imageDF)

    val q1 = resultDF.writeStream
      .format("memory")
      .queryName("images")
      .start()

    tryWithRetries(){ () =>
      assert(session.sql("select * from images").count() == 6)
    }
  }

  def resNetModel(): ImageFeaturizer = new ImageFeaturizer()
    .setInputCol(inputCol)
    .setOutputCol(outputCol)
    .setModel(resNet)

  test("the Image feature should work with the modelSchema") {
    val result = resNetModel().setCutOutputLayers(0).transform(images)
    compareToTestModel(result)
  }

  test("the Image feature should work with the modelSchema + new images") {
    val newImages = session.read
      .format(classOf[ImageFileFormat].getName)
      .load(cifarDirectory)
      .withColumnRenamed("image","cntk_images")

    val result = resNetModel().setCutOutputLayers(0).transform(newImages)
    compareToTestModel(result)
  }

  test("Image featurizer should work with ResNet50", TestBase.Extended) {
    val result = resNetModel().transform(images)
    val resVec = result.select(outputCol).collect()(0).getAs[DenseVector](0)
    assert(resVec.size == 1000)
  }

  test("Image featurizer should work with ResNet50 and powerBI", TestBase.Extended) {
    val groceriesDirectory = "/Images/Grocery/"
    val fileLocation = s"${sys.env("DATASETS_HOME")}/$groceriesDirectory"

    val images = session.readImages(fileLocation, true).coalesce(1)
    println(images.count())

    val result = resNetModel().setInputCol("image").transform(images)
      .withColumn("foo", udf({x: DenseVector => x(0).toString}, StringType)(col("out")))
      .select("foo")

    PowerBIWriter.write(result, sys.env("MML_POWERBI_URL"), Map("concurrency"->"1"))
  }

  test("test layers of network", TestBase.Extended) {
    (0 to 9).foreach({ i =>
      val model = new ImageFeaturizer()
        .setModel(resNet)
        .setInputCol(inputCol)
        .setOutputCol(outputCol)
        .setCutOutputLayers(i)
      val result = model.transform(images)
    })
  }

  val reader: MLReadable[_] = ImageFeaturizer
  override def testObjects(): Seq[TestObject[ImageFeaturizer]] = Seq(
    new TestObject(resNetModel(), images)
  )
}
