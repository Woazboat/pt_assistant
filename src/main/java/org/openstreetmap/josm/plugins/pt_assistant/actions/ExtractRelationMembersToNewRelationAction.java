package org.openstreetmap.josm.plugins.pt_assistant.actions;

import java.awt.event.ActionEvent;
import java.util.*;
import java.util.stream.Collectors;

import javax.swing.*;

import org.openstreetmap.josm.command.ChangeCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.*;
import org.openstreetmap.josm.gui.dialogs.relation.IRelationEditor;
import org.openstreetmap.josm.gui.dialogs.relation.MemberTableModel;
import org.openstreetmap.josm.gui.dialogs.relation.RelationEditor;
import org.openstreetmap.josm.gui.dialogs.relation.actions.AbstractRelationEditorAction;
import org.openstreetmap.josm.gui.dialogs.relation.actions.IRelationEditorActionAccess;
import org.openstreetmap.josm.gui.dialogs.relation.actions.IRelationEditorUpdateOn;
import org.openstreetmap.josm.plugins.pt_assistant.data.PTSegmentToExtract;
import org.openstreetmap.josm.plugins.pt_assistant.data.WayTriplet;
import org.openstreetmap.josm.plugins.pt_assistant.utils.RouteUtils;
import org.openstreetmap.josm.tools.*;

import static java.util.Collections.*;

/*
Extracts selected members to a new route relation
and substitutes them with this new route relation
in the original relation

 * @author Florian, Polyglot
 */
public class ExtractRelationMembersToNewRelationAction extends AbstractRelationEditorAction {
    public ExtractRelationMembersToNewRelationAction(IRelationEditorActionAccess editorAccess) {
        super(editorAccess, IRelationEditorUpdateOn.MEMBER_TABLE_SELECTION,
            IRelationEditorUpdateOn.MEMBER_TABLE_CHANGE,
            IRelationEditorUpdateOn.TAG_CHANGE);
        new ImageProvider("dialogs/relation", "extract_relation").getResource().attachImageIcon(this);
    }

    @Override
    public void actionPerformed(ActionEvent actionEvent) {
        final MemberTableModel memberTableModel = editorAccess.getMemberTableModel();
        IRelationEditor editor = editorAccess.getEditor();
        final Relation originalRelation = editor.getRelation();
        Relation editedRelation = new Relation(originalRelation);
        // save the current state, otherwise accidents happen
        memberTableModel.applyToRelation(editedRelation);
        editorAccess.getTagModel().applyToPrimitive(editedRelation);
        UndoRedoHandler.getInstance().add(new ChangeCommand(originalRelation, editedRelation));

        final Collection<RelationMember> selectedMembers = memberTableModel.getSelectedMembers();

        List<Command> commands = new ArrayList<>();

        String title = I18n.tr("Extract part from relation?");
        String question = I18n.tr(
            "Do you want to create a new relation with the {0} selected members?",
            selectedMembers.size()
        );
        List<Relation> parentRelations = new ArrayList<>(Utils.filteredCollection(originalRelation.getReferrers(), Relation.class));
        parentRelations.removeIf(parentRelation -> !Objects.equals(parentRelation.get("type"), "superroute"));
        final int numberOfParentRelations = parentRelations.size();
        final JCheckBox cbReplaceInSuperrouteRelations = new JCheckBox(I18n.tr(
            "This route is a member of {0} superroute {1}, add the extracted relation there instead of in this relation?",
            numberOfParentRelations,
            I18n.trn("relation", "relations", numberOfParentRelations)));
        final JCheckBox cbConvertToSuperroute = new JCheckBox(I18n.tr("Convert this relation to superroute"));
        final JCheckBox cbProposed = new JCheckBox(I18n.tr("subrelation is proposed (wenslijn)"));
        final JCheckBox cbDeviation = new JCheckBox(I18n.tr("subrelation is deviation (omleiding)"));
        final JCheckBox cbFindAllSegmentsAutomatically = new JCheckBox(I18n.tr("Process complete route relation for segments and extract into multiple sub route relations"));
        final JTextField tfNameTag = new JTextField(getEditor().getRelation().get("name"));
        ArrayList<Object> paramsList = new ArrayList<>();
        paramsList.add(question);
        paramsList.add(tfNameTag);
        // Only show cbReplaceInSuperrouteRelations when it's relevant
        if (numberOfParentRelations > 0) {
            // check the check box
            cbReplaceInSuperrouteRelations.setSelected(true);
            paramsList.add(cbReplaceInSuperrouteRelations);
        }
        if (originalRelation.hasTag("cycle_network", "cycle_highway")) {
            paramsList.add(cbDeviation);
            paramsList.add(cbProposed);
        }
        paramsList.add(cbConvertToSuperroute);
        if (RouteUtils.isVersionTwoPTRoute(originalRelation)) {
            cbConvertToSuperroute.setSelected(true);
            cbFindAllSegmentsAutomatically.setSelected(true);
            paramsList.add(cbFindAllSegmentsAutomatically);
        }
        paramsList.add(cbConvertToSuperroute);
        Object[] params = paramsList.toArray(new Object[0]); // paramsList.size()]);
        if (
            JOptionPane.showConfirmDialog(
                getMemberTable(),
                params,
                title,
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE
            ) == JOptionPane.OK_OPTION
        ) {
            if (cbFindAllSegmentsAutomatically.isSelected()) {
                splitInSegments(originalRelation, cbConvertToSuperroute.isSelected());
            } else {
                final Relation clonedRelation = new Relation(originalRelation);
                PTSegmentToExtract segment = new PTSegmentToExtract(clonedRelation,
                    Arrays.stream(memberTableModel.getSelectedIndices()).boxed().collect(Collectors.toList()));
                segment.put("name", tfNameTag.getText());
                if (cbProposed.isSelected()) {
                    segment.put("state", "proposed");
                    segment.put("name", tfNameTag.getText() + " (wenslijn)");
                }
                if (cbDeviation.isSelected()) {
                    segment.put("name", tfNameTag.getText() + " (omleiding)");
                }
                Relation extractedRelation = segment.extractToRelation(
                    new ArrayList<>(Arrays.asList("type", "route", "cycle_network", "network", "operator", "ref")),
                    true);
//                    todo cbReplaceInSuperrouteRelations.isSelected());
                if (extractedRelation != null) {
                    if (extractedRelation.isNew() && cbConvertToSuperroute.isSelected()) {
                        clonedRelation.put("type", "superroute");
                    }
                    commands.add(new ChangeCommand(originalRelation, clonedRelation));
                    addExtractedRelationToParentSuperrouteRelations(originalRelation, commands,
                        parentRelations, segment.getIndices(), extractedRelation);
                    UndoRedoHandler.getInstance().add(
                        new SequenceCommand(I18n.tr("Extract ways to relation"), commands));

                    RelationEditor extraEditor = RelationEditor.getEditor(
                        getEditor().getLayer(), extractedRelation, emptyList());
                    extraEditor.setVisible(true);
                    extraEditor.setAlwaysOnTop(true);
                }
            }
            editor.reloadDataFromRelation();
        }
    }

    public void splitInSegments(Relation originalRelation, Boolean convertToSuperroute) {
        final Relation clonedRelation = new Relation(originalRelation);
        final long clonedRelationId = clonedRelation.getId();
        boolean startNewSegment = false;
        final List<RelationMember> members = clonedRelation.getMembers();
        PTSegmentToExtract segment = new PTSegmentToExtract(clonedRelation);
        segment.addPTWay(members.size() - 1);
        for (int i = members.size() - 2; i >= 1; i--) {
            final Way previousWay = getIfWay(members, i - 1);
            final Way currentWay = getIfWay(members, i);
            if (currentWay == null) {
                // Going backward through all the ways, the stop members were reached
                segment.extractToRelation(new ArrayList<>(Arrays.asList("type", "route")),
                    true);
                break;
            }
            final Way nextWay = getIfWay(members, i + 1);

            if (startNewSegment) {
                segment.extractToRelation(new ArrayList<>(Arrays.asList("type", "route")),
                                          true);
                segment = new PTSegmentToExtract(clonedRelation);
                startNewSegment = false;
            }
            segment.addPTWay(i);

            long previousWayId = 0;
            if (previousWay != null) {previousWayId = previousWay.getId();}
            List<Relation> parentRouteRelations = new ArrayList<>(Utils.filteredCollection(currentWay.getReferrers(), Relation.class));
            for (Relation parentRoute : parentRouteRelations) {
                if (parentRoute.getId() != clonedRelationId && RouteUtils.isVersionTwoPTRoute(parentRoute)) {
                    for (WayTriplet<Way,Way,Way> waysInParentRoute : findPreviousAndNextWayInRoute(parentRoute.getMembers(), currentWay)) {
                        if (waysInParentRoute.previousWay == null || waysInParentRoute.nextWay == null) {
                            // If there is no way before or a way after in the parent route
                            // this was the first or the last way in the parent route
                            // so a split is needed
                            // todo this doesn't work as intended yet
                            startNewSegment = true;
                            String route_ref = parentRoute.get("route_ref");
                            if (waysInParentRoute.nextWay == null && route_ref != null) {
                                new PTSegmentToExtract(parentRoute, false);
                            }
                        }
                        long previousWayInParentRouteId = waysInParentRoute.previousWay != null ? waysInParentRoute.previousWay.getId() : 0;
                        if (isItineraryInSameDirection(nextWay, waysInParentRoute.nextWay, previousWayId, previousWayInParentRouteId)) {
                            if (!startNewSegment && previousWayInParentRouteId != 0
                                && previousWayId != previousWayInParentRouteId) {
                                // if one of the parent relations has a different previous way
                                // it's time to start a new segment
                                startNewSegment = true;
                            }
                            /*
                             If the next way after the previous way's parent
                             routes isn't the same as currentWay a split is also needed
                            */
                            if (!startNewSegment) {
                                if (previousWay != null) {
                                    startNewSegment = isNextWayOfPreviousWayDifferentFromCurrentWayInAtLeastOneOfTheParentsOfPreviousWay(
                                        previousWay, currentWay);
                                }
                            }
                            segment.addLineIdentifier(parentRoute.get("ref"));
                            segment.addColour(parentRoute.get("colour"));
                        }
                        if (startNewSegment) {
                            break;
                        }
                    }
                }
            }
        }
        if (convertToSuperroute) {
            clonedRelation.put("type", "superroute");
        }
        UndoRedoHandler.getInstance().add(new ChangeCommand(originalRelation, clonedRelation));
    }

    public Way getIfWay(List<RelationMember> members, int index) {
        RelationMember member = members.get(index);
        if (member.isWay()) return member.getWay();
        return null;
    }

    public boolean isNextWayOfPreviousWayDifferentFromCurrentWayInAtLeastOneOfTheParentsOfPreviousWay(Way previousWay, Way currentWay) {
        List<Relation> parentRoutesOfPreviousWay = new ArrayList<>(Utils.filteredCollection(previousWay.getReferrers(), Relation.class));
        for (Relation parentRouteOfPreviousWay : parentRoutesOfPreviousWay) {
            if (RouteUtils.isVersionTwoPTRoute(parentRouteOfPreviousWay)) {
                List<WayTriplet<Way,Way,Way>> prevAndNextParent = findPreviousAndNextWayInRoute(parentRouteOfPreviousWay.getMembers(), previousWay);
                Way nextWayInParentRouteOfPreviousWay = null;
                if (prevAndNextParent.size() > 0) {
                    // todo what if the way occurs more than once in the parent route of the previous way?
                    nextWayInParentRouteOfPreviousWay = prevAndNextParent.get(0).nextWay;
//                    if (nextWayInParentRouteOfPreviousWay != null) {
//                        String nextWayInParentRouteOfPreviousWayName = nextWayInParentRouteOfPreviousWay.get("name");
//                    }
                }
                if (nextWayInParentRouteOfPreviousWay != null
                    && nextWayInParentRouteOfPreviousWay.getId() != currentWay.getId()) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isItineraryInSameDirection(Way nextWayMember, Way nextWayInParentRoute, long previousWayId, long previousWayInParentRouteId) {
        return !(nextWayInParentRoute != null && previousWayId == nextWayInParentRoute.getId() ||
            previousWayInParentRouteId != 0 && nextWayMember.getId() == previousWayInParentRouteId);
    }

    /**
     * for all occurrences of wayToLocate this method returns the way before it and the way after it
     * @param members          The members list of the relation
     * @param wayToLocate      The way to locate in the list
     * @return a list of way triplets
     */
    private List<WayTriplet<Way,Way,Way>> findPreviousAndNextWayInRoute(List<RelationMember> members, Way wayToLocate) {
        final long wayToLocateId = wayToLocate.getId();
        Way previousWay;
        Way nextWay = null;
        boolean foundWay = false;
        List<WayTriplet<Way,Way,Way>> wayTriplets = new ArrayList<>();
        for (int j = members.size() - 1; j>=0 ; j--) {
            RelationMember rm = members.get(j);
            if (rm.isWay() && RouteUtils.isPTWay(rm)) {
                previousWay = rm.getWay();
                if (foundWay) {
                    if (previousWay == wayToLocate) {
                        previousWay = null;
                    }
                    wayTriplets.add(0, new WayTriplet<>(previousWay,wayToLocate,nextWay));
                    nextWay = null;
                    foundWay = false;
                    continue;
                }
                if (previousWay.getId() == wayToLocateId) {
                    foundWay = true;
                } else {
                    nextWay = previousWay;
                }
            }
        }
        return wayTriplets;
    }
    /** This method modifies clonedRelation in place if substituteWaysWithRelation is true
     *  and if ways are extracted into a new relation
     *  It takes a list of (preferably) consecutive indices
     *  If there is already a relation with the same members in the same order
     *  it returns a pointer to that relation
     *  otherwise it returns a new relation with a negative id,
     *  which still needs to be added using addCommand()*/

    private void addExtractedRelationToParentSuperrouteRelations(Relation originalRelation, List<Command> commands, List<Relation> parentRelations, List<Integer> selectedIndices, Relation extractedRelation) {
        for (Relation superroute : parentRelations) {
            int index = 0;
            for (RelationMember member : superroute.getMembers()) {
                if (member.getMember().getId() == originalRelation.getId()) {
                    Relation clonedParentRelation = new Relation(superroute);
                    if (selectedIndices.get(0) != 0) {
                        // if the user selected a block that didn't start with
                        // the first member, add the extracted relation after
                        // the position where this relation was in the parent
                        index++;
                    }
                    index = PTSegmentToExtract.limitIntegerTo(index, superroute.getMembersCount());
                    clonedParentRelation.addMember(index, new RelationMember("", extractedRelation));
                    commands.add(new ChangeCommand(superroute, clonedParentRelation));
                    break;
                }
                index++;
            }
        }
    }

    @Override
    public boolean isExpertOnly() {
        return true;
    }

    @Override
    protected void updateEnabledState() {
        final boolean newEnabledState = !editorAccess.getSelectionTableModel().getSelection().isEmpty()
            && Optional.ofNullable(editorAccess.getTagModel().get("type")).filter(it -> it.getValue().matches("route|superroute")).isPresent();
            // && !editorAccess.getEditor().isDirtyRelation(); // This seems to cause a problem

        if (newEnabledState) {
            putValue(SHORT_DESCRIPTION, I18n.tr("Extract selected part of the route into new relation"));
        } else {
            putValue(SHORT_DESCRIPTION, I18n.tr("Extract into new relation (needs type=route/superroute tag and at least one selected relation member)"));
        }
        setEnabled(newEnabledState);
    }
}
