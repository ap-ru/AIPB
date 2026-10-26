package com.aipb.widget;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Who sees which definitions, and the rules for submitting a personal widget to the global library. */
class WidgetLibraryTest {

    private WidgetDefinitionRepository repo;
    private WidgetLibrary library;
    private final WidgetDefinition starter = def("client-exposure", "system", null);           // saved before roles: global
    private final WidgetDefinition global = def("book-pipeline", "system", WidgetDefinition.GLOBAL);
    private final WidgetDefinition mine = def("my-widget", "demo-builder", WidgetDefinition.PERSONAL);
    private final WidgetDefinition theirs = def("their-widget", "demo-reviewer", WidgetDefinition.PERSONAL);

    @BeforeEach
    void setUp() {
        repo = mock(WidgetDefinitionRepository.class);
        when(repo.findAll()).thenReturn(List.of(starter, global, mine, theirs));
        for (WidgetDefinition d : List.of(starter, global, mine, theirs)) when(repo.findById(d.getId())).thenReturn(Optional.of(d));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        library = new WidgetLibrary(repo, mock(WidgetVersionRepository.class), mock(WidgetSpecValidator.class), mock(WidgetDataService.class), new ObjectMapper());
    }

    @Test
    void anActorSeesTheGlobalLibraryAndTheirOwnPersonalWidgets() {
        assertThat(library.list("demo-builder")).extracting(WidgetDefinition::getId).containsExactlyInAnyOrder("client-exposure", "book-pipeline", "my-widget");
        assertThat(library.list("demo-banker")).extracting(WidgetDefinition::getId).containsExactlyInAnyOrder("client-exposure", "book-pipeline");
        assertThatThrownBy(() -> library.get("their-widget", "demo-builder")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void submittingMarksAPersonalWidgetPendingAndKeepsItPersonal() {
        WidgetDefinition d = library.submitForReview("my-widget", "demo-builder", "  checked against the finance pack ");
        assertThat(d.reviewStatus()).isEqualTo("pending");
        assertThat(d.getReview().getSubmittedBy()).isEqualTo("demo-builder");
        assertThat(d.getReview().getNote()).isEqualTo("checked against the finance pack");
        assertThat(d.global()).isFalse();
        assertThat(library.list("demo-banker")).extracting(WidgetDefinition::getId).doesNotContain("my-widget");
    }

    @Test
    void onlyTheOwnerCanSubmitAPersonalWidgetOnce() {
        assertThatThrownBy(() -> library.submitForReview("their-widget", "demo-builder", null)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> library.submitForReview("book-pipeline", "demo-builder", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already in the global library");
        library.submitForReview("my-widget", "demo-builder", null);
        assertThatThrownBy(() -> library.submitForReview("my-widget", "demo-builder", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already waiting");
    }

    private static WidgetDefinition def(String id, String owner, String scope) {
        WidgetDefinition d = new WidgetDefinition();
        d.setId(id);
        d.setTitle(id);
        d.setOwner(owner);
        d.setScope(scope);
        return d;
    }
}
