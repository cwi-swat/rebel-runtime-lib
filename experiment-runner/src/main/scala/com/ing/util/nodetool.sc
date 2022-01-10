import org.apache.cassandra.tools.{NodeProbe, NodeTool}
import org.apache.cassandra.tools.nodetool.Status

//NodeTool.main("-p", "7199", "-h", "localhost", "status")

//new Status().run()

//println(new NodeProbe("localhost", 7199).getLiveNodes)
//println(new NodeProbe("ec2-52-29-254-199.eu-central-1.compute.amazonaws.com", 7199).getLiveNodes)
val probe = new NodeProbe("18.196.77.254", 7199)
println(probe.getLiveNodes)
probe.getUnreachableNodes

println("done")

//io.airlift.command.ParseArgumentsMissingException