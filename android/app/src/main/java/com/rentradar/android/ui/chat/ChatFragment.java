package com.rentradar.android.ui.chat;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.rentradar.android.R;
import com.rentradar.android.data.Result;
import com.rentradar.android.data.remote.dto.PredictionDtos;
import com.rentradar.android.databinding.FragmentChatBinding;
import com.rentradar.android.ui.result.ResultFragment;

import java.util.List;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * The conversational intake. Draws the transcript the ViewModel keeps and
 * forwards every tap and every typed line to it. Holds no state of its own, so
 * a rotation costs nothing.
 */
@AndroidEntryPoint
public class ChatFragment extends Fragment {

    private FragmentChatBinding binding;
    private ChatViewModel viewModel;
    private ChatAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentChatBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(this).get(ChatViewModel.class);

        binding.chatGreetingTitle.setText(ChatGreetings.pick(requireContext()));

        adapter = new ChatAdapter(new ChatAdapter.OnChip() {
            @Override
            public void onChosen(ChatMessage message, String label) {
                viewModel.onChosen(message, label);
            }

            @Override
            public void onToggled(ChatMessage message, String label, boolean selected) {
                viewModel.onToggled(message, label, selected);
            }
        });
        LinearLayoutManager layout = new LinearLayoutManager(requireContext());
        layout.setStackFromEnd(true);
        binding.chatList.setLayoutManager(layout);
        binding.chatList.setAdapter(adapter);

        binding.chatBack.setOnClickListener(v ->
                NavHostFragment.findNavController(this).navigateUp());
        binding.chatRestart.setOnClickListener(v -> viewModel.restart());
        binding.chatOptions.setOnClickListener(v -> viewModel.showChoices());
        binding.chatSend.setOnClickListener(v -> send());

        viewModel.messages().observe(getViewLifecycleOwner(), this::renderMessages);
        viewModel.progress().observe(getViewLifecycleOwner(), answered ->
                binding.chatProgress.setText(getString(
                        R.string.chat_progress, answered == null ? 0 : answered, IntakeSlots.TOTAL)));
        viewModel.greetingVisible().observe(getViewLifecycleOwner(), visible ->
                binding.chatGreeting.setVisibility(Boolean.TRUE.equals(visible) ? View.VISIBLE : View.GONE));
        viewModel.submission().observe(getViewLifecycleOwner(), this::renderSubmission);
    }

    private void send() {
        String text = binding.chatInput.getText() == null
                ? "" : binding.chatInput.getText().toString();
        if (text.trim().isEmpty()) {
            return;
        }
        binding.chatInput.setText("");
        viewModel.typed(text);
    }

    private void renderMessages(@Nullable List<ChatMessage> rows) {
        if (rows == null) {
            return;
        }
        adapter.submit(rows);
        if (!rows.isEmpty()) {
            binding.chatList.post(() -> {
                if (binding != null) {
                    binding.chatList.scrollToPosition(rows.size() - 1);
                }
            });
        }
    }

    private void renderSubmission(@Nullable Result<PredictionDtos.PredictionResponse> result) {
        if (result == null) {
            setBusy(false);
            return;
        }
        setBusy(result.isLoading());

        if (result.status == Result.Status.SUCCESS && result.data != null) {
            String propertyId = result.data.propertyId;
            if (propertyId == null) {
                // The ViewModel has already written the failure into the thread.
                return;
            }
            hideKeyboard();
            Bundle args = ResultFragment.argsOf(
                    propertyId, viewModel.askingPrice(), viewModel.locality());
            viewModel.clearSubmission();
            NavHostFragment.findNavController(this).navigate(R.id.action_chat_to_result, args);
        }
    }

    private void setBusy(boolean busy) {
        binding.chatSend.setEnabled(!busy);
        binding.chatOptions.setEnabled(!busy);
        binding.chatInput.setEnabled(!busy);
        binding.chatSend.setAlpha(busy ? 0.5f : 1f);
    }

    private void hideKeyboard() {
        View focus = requireActivity().getCurrentFocus();
        if (focus == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager)
                requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
    }

    @Override
    public void onDestroyView() {
        binding.chatList.setAdapter(null);
        binding = null;
        super.onDestroyView();
    }
}