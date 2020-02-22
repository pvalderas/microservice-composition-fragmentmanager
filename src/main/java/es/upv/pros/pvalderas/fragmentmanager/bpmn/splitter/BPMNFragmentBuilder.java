package es.upv.pros.pvalderas.fragmentmanager.bpmn.splitter;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.Node;
import org.dom4j.QName;
import org.jaxen.JaxenException;

import es.upv.pros.pvalderas.composition.bpmn.utils.NameSpaceManager;
import es.upv.pros.pvalderas.composition.bpmn.utils.XMLQuery;

public class BPMNFragmentBuilder {
	private Document documentPool;
	private XMLQuery query;
	private NameSpaceManager nsm;
	private List<String> messageFlows;
	private List<String> messages;
	private Map<String, String> catchEvents;
	private Map<String, String> throwEvents;
	private String microserviceId;
	private String fragmentId;
	
	private String compositionName;
	
	public String getMicroserviceId() {
		return this.microserviceId;
	}
	
	public String getFragmentId() {
		return this.fragmentId;
	}
	

	protected BPMNFragmentBuilder(String compositionName, Node poolParticipant, XMLQuery query) throws JaxenException{
		this.compositionName=compositionName;
		this.query=query;
		this.nsm=NameSpaceManager.getCurrentInstance();
		this.documentPool=getNewPool(poolParticipant);
	}

	private Document getNewPool(Node poolParticipant) throws JaxenException{
		this.messageFlows=new ArrayList<String>();
		this.messages=new ArrayList<String>();
		this.catchEvents=new Hashtable<String,String>();
		this.throwEvents=new Hashtable<String,String>();
		
		String participantId=poolParticipant.valueOf("@id");
		String processId=poolParticipant.valueOf("@processRef");
		this.microserviceId=poolParticipant.valueOf("@name");
		this.fragmentId=compositionName+"_"+microserviceId+"_fragment"; //modified
		
		Document documentPool=DocumentHelper.createDocument();
		
		Element root=addRootElement(documentPool, this.fragmentId);
		
		Node poolMicroservice=(Node)poolParticipant.clone();
		root.add(this.getParticipants(poolMicroservice,processId));
		
		for(String messageId:this.messages){
			 Node messageNode=DocumentHelper.createElement(new QName("message", nsm.getNameSpace("bpmn")));
			 ((Element)messageNode).addAttribute("id", messageId);
			 ((Element)messageNode).addAttribute("name", compositionName+"_"+messageId+"Message");
			 root.add(messageNode);
		}	
		
		Node process=this.getProcessByID(processId);
		Node processMicroservice=(Node)process.clone();
		root.add(processMicroservice);
		
		root.add(getCollapsedProcess());
		
		root.add(getDiagram(root,participantId));
		
		((Element)processMicroservice).addAttribute("id",this.compositionName+"_"+processId); //añadido
		((Element)poolMicroservice).addAttribute("processRef",this.compositionName+"_"+processId); //añadido
		
		return documentPool;
	}
	
	private String getMessageId(String name){
		return name.replaceAll(" ", "").replaceAll(Character.toString((char)160), "");
	}
	
	private Element addRootElement(Document documentPool, String id) throws JaxenException{
		Element root = DocumentHelper.createElement(new QName("definitions", nsm.getNameSpace("bpmn")));
		
		for(Map.Entry<String,String> uri:nsm.getAllUris().entrySet()){
			if(!uri.getKey().equals("bpmn")){
				root.addNamespace(uri.getKey(), uri.getValue());
			}
		}
		
		Node definitions=query.selectSingleNode("//bpmn:definitions");
		root.addAttribute("id", id);
		//root.addAttribute("name", definitions.valueOf("@id"));
		root.addAttribute("targetNamespace", definitions.valueOf("@targetNamespace"));
		
		documentPool.add(root);
		return root;
	}
	
	private Node getProcessByID(String processID) throws JaxenException{
		String processQuery = "//bpmn:process[@id='%%']";	
		Node process=query.selectSingleNode(processQuery.replace("%%", processID));
		
		List<Node> tasks=query.selectNodes("bpmn:serviceTask",process);
		for(Node task:tasks){
			((Element)task).addAttribute("camunda:delegateExpression","${serviceClass}");
		}
		
		((Element)process).addAttribute("isExecutable","true");
		
		return process;
	}
	
	private Element getCollapsedProcess(){
		Element collapsedPool = DocumentHelper.createElement(new QName("process", nsm.getNameSpace("bpmn")));
		collapsedPool.addAttribute("id", "collapsedPoolProcess");
		return collapsedPool;
	}
	
	private Element getParticipants(Node pool, String processRef) throws JaxenException{
		Element collaboration = DocumentHelper.createElement(new QName("collaboration", nsm.getNameSpace("bpmn")));
		collaboration.addAttribute("id", "collaboration");
		
		Element collapsedParticipant=collaboration.addElement(new QName("participant", nsm.getNameSpace("bpmn")));
		collapsedParticipant.addAttribute("id", "collapsedPoolParticipant");
		collapsedParticipant.addAttribute("name", "EVENT BUS");
		collapsedParticipant.addAttribute("processRef", "collapsedPoolProcess");
		
		collaboration.add(pool);
		
		for(Node message:getStartMessages(processRef)){
			collaboration.add(message);
		};
		for(Node message:getEndMessages(processRef)){
			collaboration.add(message);
		};
		
		return collaboration;
	}
	
	private List<Node> getStartMessages(String processId) throws JaxenException{
		List<Node> startMessages=new ArrayList<Node>();
		List<Node> startEvents = query.selectNodes("(//bpmn:process[@id='"+processId+"']/bpmn:startEvent | //bpmn:process[@id='"+processId+"']/bpmn:intermediateCatchEvent)"); 
		
		for(Node startEvent:startEvents){
			String startEventId=startEvent.valueOf("@id");
			String startEventName=startEvent.valueOf("@name");
			
			
			Element message = DocumentHelper.createElement(new QName("messageFlow", nsm.getNameSpace("bpmn")));
			message.addAttribute("id", "collapsedPoolParticipant-"+startEventId);
			message.addAttribute("sourceRef", "collapsedPoolParticipant");
			message.addAttribute("targetRef", startEventId);
			this.messageFlows.add("collapsedPoolParticipant-"+startEventId);
			startMessages.add(message);
			this.catchEvents.put("collapsedPoolParticipant-"+startEventId, startEventId);
			
			this.messages.add(getMessageId(startEventName));
			
			Node messageDef=query.selectSingleNode("(bpmn:messageEventDefinition)", startEvent); 
			((Element)messageDef).addAttribute("messageRef", getMessageId(startEventName));
		}
		
		return startMessages;
	}
	
	private List<Node> getEndMessages(String processId) throws JaxenException{
		List<Node> endMessages=new ArrayList<Node>();
		List<Node> endEvents = query.selectNodes("(//bpmn:process[@id='"+processId+"']/bpmn:endEvent | //bpmn:process[@id='"+processId+"']/bpmn:intermediateThrowEvent)"); 
		
		for(Node endEvent:endEvents){
			Node messageDef=query.selectSingleNode("(bpmn:messageEventDefinition)", endEvent);
			
			if(messageDef!=null){
				
				String endEventId=endEvent.valueOf("@id");
				String targetMicroService=getMicroService(endEventId);
				if(targetMicroService==null) targetMicroService="all";
				String endEventName=endEvent.valueOf("@name");
				
				Element message = DocumentHelper.createElement(new QName("messageFlow", nsm.getNameSpace("bpmn")));
				message.addAttribute("id", endEventId+"-collapsedPoolParticipant");
				message.addAttribute("sourceRef", endEventId);
				message.addAttribute("targetRef", "collapsedPoolParticipant");
				this.messageFlows.add(endEventId+"-collapsedPoolParticipant");	
				endMessages.add(message);
				
				this.throwEvents.put(endEventId+"-collapsedPoolParticipant", endEventId);
				
				this.messages.add(getMessageId(endEventName));
				
				((Element)messageDef).addAttribute("camunda:delegateExpression","${eventSender}");
				Element extensions = ((Element)messageDef).addElement(new QName("extensionElements", nsm.getNameSpace("bpmn")));
				Element field=extensions.addElement(new QName("field", nsm.getNameSpace("camunda")));
				field.addAttribute("name", "message");
				field.addAttribute("stringValue", this.compositionName+"_"+getMessageId(endEventName)+"Message");
				
				Element field2=extensions.addElement(new QName("field", nsm.getNameSpace("camunda")));
				field2.addAttribute("name", "microservice");
				field2.addAttribute("stringValue", targetMicroService);
			}
		}
		
		return endMessages;
	}
	
	private String getMicroService(String elementID) throws JaxenException{
		Node message=query.selectSingleNode("//bpmn:messageFlow[@sourceRef='"+elementID+"']");
		if(message!=null){
			Element process=(Element)query.selectSingleNode("//*[@id='"+message.valueOf("@targetRef")+"']").getParent();
			String processId=process.valueOf("@id");
			Node participant=query.selectSingleNode("//bpmn:participant[@processRef='"+processId+"']");
			String microserviceID=participant.valueOf("@name");	
			return microserviceID;
		}
		return null;
	}
	
	private Node getDiagram(Element root, String participantId) throws JaxenException{
		Element diagram = DocumentHelper.createElement(new QName("BPMNDiagram", nsm.getNameSpace("bpmndi")));
		diagram.addAttribute("id", "BPMNdiagram");
		
		Element plane= diagram.addElement(new QName("BPMNPlane", nsm.getNameSpace("bpmndi")));
		plane.addAttribute("id", "BPMNPlane");
		plane.addAttribute("bpmnElement", "collaboration");
		
		List<Node> elements = query.selectNodes("(//bpmndi:BPMNShape | //bpmndi:BPMNEdge)");
		
		String idQuery = "//*[@id='%%']";
		
		Integer newY=0;
		for(Node element: elements){
			String refID=element.valueOf("@bpmnElement");
			
			if(query.selectSingleNode(idQuery.replace("%%", refID), root)!=null){
				plane.add((Node)element.clone());
			}
				
			if(participantId.equals(refID)){
				Element bounds=(Element)element.selectSingleNode("dc:Bounds");
				String y=bounds.valueOf("@y");
				String height=bounds.valueOf("@height");
				
				newY=Integer.parseInt(y)+Integer.parseInt(height)+20;
				bounds.addAttribute("y", newY.toString());
				bounds.addAttribute("height", "100");
			
				
				Element collapsedView=(Element)element.clone();
				collapsedView.addAttribute("id", "viewforcollapsed");
				collapsedView.addAttribute("bpmnElement", "collapsedPoolParticipant");
				
				plane.add(collapsedView);
			}
		}
		
		for(String message:this.messageFlows){
			
			String x1,y1,x2,y2;
			
			String id=catchEvents.get(message);
			if(id==null){
				id=throwEvents.get(message);
				
				Node shape=query.selectSingleNode("//bpmndi:BPMNShape[@bpmnElement='"+id+"']/dc:Bounds");

				x1=String.valueOf(Integer.parseInt(shape.valueOf("@x"))+Integer.parseInt(shape.valueOf("@width"))/2);
				y1=String.valueOf(Integer.parseInt(shape.valueOf("@y"))+Integer.parseInt(shape.valueOf("@height")));
				
				x2=x1;
				y2=newY.toString();
			}else{
				Node shape=query.selectSingleNode("//bpmndi:BPMNShape[@bpmnElement='"+id+"']/dc:Bounds");
	
				x1=String.valueOf(Integer.parseInt(shape.valueOf("@x"))+Integer.parseInt(shape.valueOf("@width"))/2);
				y1=newY.toString();
				
				x2=x1;
				y2=String.valueOf(Integer.parseInt(shape.valueOf("@y"))+Integer.parseInt(shape.valueOf("@height")));
			}
			
			
			
			
			Element edge = DocumentHelper.createElement(new QName("BPMNEdge", nsm.getNameSpace("bpmndi")));
			edge.addAttribute("id", message+"_di");
			edge.addAttribute("bpmnElement", message);
			Element waypoint1 = DocumentHelper.createElement(new QName("waypoint", nsm.getNameSpace("di")));
			waypoint1.addAttribute("x", x1);
			waypoint1.addAttribute("y", y1);
			edge.add(waypoint1);
			Element waypoint2 = DocumentHelper.createElement(new QName("waypoint", nsm.getNameSpace("di")));
			waypoint2.addAttribute("x", x2);
			waypoint2.addAttribute("y", y2);
			edge.add(waypoint2);
			plane.add(edge);		
		}
		
		return diagram;
	}
	
	protected String asXML(){
		return documentPool.asXML().toString();
	}
}
