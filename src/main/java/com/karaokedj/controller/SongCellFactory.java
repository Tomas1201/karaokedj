package com.karaokedj.controller;

import com.karaokedj.model.SongMetadata;
import javafx.scene.control.ListCell;

public class SongCellFactory extends javafx.scene.control.ListCell<SongMetadata> {

    @Override
    protected void updateItem(SongMetadata item, boolean empty) {
        super.updateItem(item, empty);

        if (empty || item == null) {
            setText(null);
            setGraphic(null);
            setStyle("");
        } else {
            setText(item.toString());
            setStyle("-fx-text-fill: #cdd6f4; -fx-font-size: 12;");
        }
    }
}
