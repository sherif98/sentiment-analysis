package edu.twitter.model.impl

import org.apache.spark.SparkContext
import org.apache.spark.sql.DataFrame

/**
  * Class responsible for parsing JSON files.
  *
  * @param sc : SparkContext used to read training data
  */
class JsonParser(sc: SparkContext) {

  /**
    * parse Json files. load the data into Spark. Spark makes it easy to load JSON-formatted data into a dataframe
    *
    * @param path path to json files
    * @return DataFrame represent json objects
    */
  def parse(path: String): DataFrame = {
    val sqlContext = new org.apache.spark.sql.SQLContext(sc)
    sqlContext.read.json(path)
  }
}