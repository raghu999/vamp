package io.vamp.core.model.workflow

import io.vamp.core.model.artifact.{Artifact, Reference}

object Workflow {

  object Language extends Enumeration {
    val JavaScript = Value
  }

}

trait Workflow extends Artifact

case class WorkflowReference(name: String) extends Reference with Workflow

case class DefaultWorkflow(name: String, script: String) extends Workflow {
  def language = Workflow.Language.JavaScript
}


trait Trigger

case class TimeTrigger(time: String) extends Trigger

case class DeploymentTrigger(deployment: String) extends Trigger

case class EventTrigger(tags: List[String]) extends Trigger


case class ScheduledWorkflow(name: String, workflow: Workflow, trigger: Trigger) extends Artifact