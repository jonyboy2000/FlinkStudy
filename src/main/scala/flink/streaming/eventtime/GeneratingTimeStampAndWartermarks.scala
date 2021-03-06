package flink.streaming.eventtime

import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import flink.streaming.basic.datasource.StreamCreator
import org.apache.flink.api.common.io.FileInputFormat
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.functions.AssignerWithPeriodicWatermarks
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.watermark.Watermark
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows
import org.apache.flink.streaming.api.windowing.time.Time
import org.apache.flink.streaming.api.windowing.windows.TimeWindow
import org.apache.flink.util.Collector

import scala.io.Source
import scala.math._

object GeneratingTimeStampAndWartermarks {
  val PATH = "src/main/resources/myEvent.csv"

  def main(args: Array[String]): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.IngestionTime)
    env.setParallelism(1)

    //val input = env.readFile(new MyFileInputFormat(PATH), PATH)
    val stream = env.fromCollection(StreamCreator.sourceWithTimestamp(List.range(1, 10), 500))
    val input = stream.map(t => MyEvent(t._1.toLong,"msg-" +t._1, t._2, t._3))

    input
      .assignTimestampsAndWatermarks(new TimeLagPeriodicWatermarks)
      .windowAll(TumblingEventTimeWindows.of(Time.seconds(3)))
      .apply((time: TimeWindow, input: Iterable[MyEvent], out: Collector[String]) => {
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss SSS"))
        out.collect(s"time: $time, Count: ${input.size} : ${input.map(_.index.toString).reduce(_+"-"+_)}")
      })
      .print()

    env.execute("Example event time 1")
  }

  case class MyEvent(index: Long, msg:String, timestamp: String, timestamp_ms: Long)

  // formatter for CSV file to MyEvent format
  class MyFileInputFormat(path: String) extends FileInputFormat[MyEvent] {
    val inputFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss SSS")

    val lines = Source.fromFile(path).getLines().toList
    var count = 0
    val maxCount = lines.length

    override def nextRecord(reuse: MyEvent): MyEvent = {
      val columns = lines(count).split(",")
      val record = MyEvent(columns(0).toLong, columns(1), columns(2), inputFormat.parse(columns(2)).getTime)
      count += 1
      record
    }
    override def reachedEnd(): Boolean = count >= maxCount
  }

  // Watermarks for In Order
  class TimeLagPeriodicWatermarks extends AssignerWithPeriodicWatermarks[MyEvent] {
    val maxTimeLag  = 3500L; // 3.5 seconds
    override def extractTimestamp(element: MyEvent, previousElementTimestamp: Long): Long = element.timestamp_ms
    override def getCurrentWatermark: Watermark = new Watermark(System.currentTimeMillis() - maxTimeLag )
  }

  // Watermarks for Out of Order
  class OutOfOrderPeriodicWatermarks extends AssignerWithPeriodicWatermarks[MyEvent] {
    val maxOutOfOrderness = 3500L; // 3.5 seconds
    var currentMaxTimestamp: Long = 0

    override def extractTimestamp(element: MyEvent, previousElementTimestamp: Long): Long = {
      val timestamp = element.timestamp_ms
      currentMaxTimestamp = max(timestamp, currentMaxTimestamp)
      timestamp;
    }
    override def getCurrentWatermark(): Watermark = new Watermark(currentMaxTimestamp - maxOutOfOrderness)
  }
}
