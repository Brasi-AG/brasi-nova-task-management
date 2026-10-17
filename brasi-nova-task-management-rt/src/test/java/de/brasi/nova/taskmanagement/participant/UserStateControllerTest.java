package de.brasi.nova.taskmanagement.participant;

import static org.mockito.Mockito.verify;

import de.brasi.nova.taskmanagement.participant.model.MarkerInput;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit-Tests fuer {@link UserStateController} (duenner Delegations-Test).
 *
 * <p>Beweist, dass jeder Handler genau an die entsprechende {@link MarkerService}-Methode
 * delegiert. Die fachliche Logik wird im {@link MarkerServiceTest} verifiziert.
 */
@ExtendWith(MockitoExtension.class)
class UserStateControllerTest {

  private static final UUID TASK_ID = UUID.randomUUID();

  @Mock private MarkerService markerService;

  private UserStateController controller;

  @BeforeEach
  void setUp() {
    controller = new UserStateController(markerService);
  }

  @Test
  @DisplayName("pin_delegiert_an_markerService_setPinned")
  void pin_delegiert_an_markerService_setPinned() {
    controller.pin(TASK_ID, new MarkerInput(true));
    verify(markerService).setPinned(TASK_ID, true);
  }

  @Test
  @DisplayName("flag_delegiert_an_markerService_setFlagged")
  void flag_delegiert_an_markerService_setFlagged() {
    controller.flag(TASK_ID, new MarkerInput(false));
    verify(markerService).setFlagged(TASK_ID, false);
  }

  @Test
  @DisplayName("mute_delegiert_an_markerService_setMuted")
  void mute_delegiert_an_markerService_setMuted() {
    controller.mute(TASK_ID, new MarkerInput(true));
    verify(markerService).setMuted(TASK_ID, true);
  }
}
