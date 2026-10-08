package io.github.fishpimp.exiflab.core.designsystem.icon

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import io.github.fishpimp.exiflab.core.designsystem.R

/** Material Symbols Rounded, bundled as vector drawables (generated list; see res/drawable/ic_*.xml). */
public object ExifIcons {
    @DrawableRes public val Add: Int = R.drawable.ic_add
    @DrawableRes public val AddCircle: Int = R.drawable.ic_add_circle
    @DrawableRes public val AddLocationAlt: Int = R.drawable.ic_add_location_alt
    @DrawableRes public val AddPhotoAlternate: Int = R.drawable.ic_add_photo_alternate
    @DrawableRes public val AlternateEmail: Int = R.drawable.ic_alternate_email
    @DrawableRes public val ArrowBack: Int = R.drawable.ic_arrow_back
    @DrawableRes public val ArrowDownward: Int = R.drawable.ic_arrow_downward
    @DrawableRes public val ArrowDropDown: Int = R.drawable.ic_arrow_drop_down
    @DrawableRes public val ArrowForward: Int = R.drawable.ic_arrow_forward
    @DrawableRes public val ArrowUpward: Int = R.drawable.ic_arrow_upward
    @DrawableRes public val AspectRatio: Int = R.drawable.ic_aspect_ratio
    @DrawableRes public val AutoDelete: Int = R.drawable.ic_auto_delete
    @DrawableRes public val Backup: Int = R.drawable.ic_backup
    @DrawableRes public val Badge: Int = R.drawable.ic_badge
    @DrawableRes public val BlurOn: Int = R.drawable.ic_blur_on
    @DrawableRes public val Bolt: Int = R.drawable.ic_bolt
    @DrawableRes public val BrightnessAuto: Int = R.drawable.ic_brightness_auto
    @DrawableRes public val Calculate: Int = R.drawable.ic_calculate
    @DrawableRes public val CalendarToday: Int = R.drawable.ic_calendar_today
    @DrawableRes public val Camera: Int = R.drawable.ic_camera
    @DrawableRes public val CameraRoll: Int = R.drawable.ic_camera_roll
    @DrawableRes public val Cancel: Int = R.drawable.ic_cancel
    @DrawableRes public val Check: Int = R.drawable.ic_check
    @DrawableRes public val CheckBox: Int = R.drawable.ic_check_box
    @DrawableRes public val CheckBoxOutlineBlank: Int = R.drawable.ic_check_box_outline_blank
    @DrawableRes public val CheckCircle: Int = R.drawable.ic_check_circle
    @DrawableRes public val Checklist: Int = R.drawable.ic_checklist
    @DrawableRes public val ChevronLeft: Int = R.drawable.ic_chevron_left
    @DrawableRes public val ChevronRight: Int = R.drawable.ic_chevron_right
    @DrawableRes public val CleaningServices: Int = R.drawable.ic_cleaning_services
    @DrawableRes public val Close: Int = R.drawable.ic_close
    @DrawableRes public val CloseFullscreen: Int = R.drawable.ic_close_fullscreen
    @DrawableRes public val CloudOff: Int = R.drawable.ic_cloud_off
    @DrawableRes public val Code: Int = R.drawable.ic_code
    @DrawableRes public val ContentCopy: Int = R.drawable.ic_content_copy
    @DrawableRes public val Contrast: Int = R.drawable.ic_contrast
    @DrawableRes public val DarkMode: Int = R.drawable.ic_dark_mode
    @DrawableRes public val DataObject: Int = R.drawable.ic_data_object
    @DrawableRes public val DataUsage: Int = R.drawable.ic_data_usage
    @DrawableRes public val Deblur: Int = R.drawable.ic_deblur
    @DrawableRes public val Delete: Int = R.drawable.ic_delete
    @DrawableRes public val DeleteSweep: Int = R.drawable.ic_delete_sweep
    @DrawableRes public val Description: Int = R.drawable.ic_description
    @DrawableRes public val DoNotDisturbOn: Int = R.drawable.ic_do_not_disturb_on
    @DrawableRes public val DoneAll: Int = R.drawable.ic_done_all
    @DrawableRes public val Download: Int = R.drawable.ic_download
    @DrawableRes public val Draft: Int = R.drawable.ic_draft
    @DrawableRes public val DragHandle: Int = R.drawable.ic_drag_handle
    @DrawableRes public val Draw: Int = R.drawable.ic_draw
    @DrawableRes public val Edit: Int = R.drawable.ic_edit
    @DrawableRes public val EditCalendar: Int = R.drawable.ic_edit_calendar
    @DrawableRes public val EditLocationAlt: Int = R.drawable.ic_edit_location_alt
    @DrawableRes public val EditNote: Int = R.drawable.ic_edit_note
    @DrawableRes public val Encrypted: Int = R.drawable.ic_encrypted
    @DrawableRes public val Error: Int = R.drawable.ic_error
    @DrawableRes public val Event: Int = R.drawable.ic_event
    @DrawableRes public val Explore: Int = R.drawable.ic_explore
    @DrawableRes public val Exposure: Int = R.drawable.ic_exposure
    @DrawableRes public val FactCheck: Int = R.drawable.ic_fact_check
    @DrawableRes public val FileCopy: Int = R.drawable.ic_file_copy
    @DrawableRes public val FileOpen: Int = R.drawable.ic_file_open
    @DrawableRes public val FilterCenterFocus: Int = R.drawable.ic_filter_center_focus
    @DrawableRes public val FilterList: Int = R.drawable.ic_filter_list
    @DrawableRes public val Fingerprint: Int = R.drawable.ic_fingerprint
    @DrawableRes public val Folder: Int = R.drawable.ic_folder
    @DrawableRes public val FolderOpen: Int = R.drawable.ic_folder_open
    @DrawableRes public val FormatColorFill: Int = R.drawable.ic_format_color_fill
    @DrawableRes public val Fullscreen: Int = R.drawable.ic_fullscreen
    @DrawableRes public val GppMaybe: Int = R.drawable.ic_gpp_maybe
    @DrawableRes public val GridView: Int = R.drawable.ic_grid_view
    @DrawableRes public val HdrOn: Int = R.drawable.ic_hdr_on
    @DrawableRes public val Help: Int = R.drawable.ic_help
    @DrawableRes public val HideImage: Int = R.drawable.ic_hide_image
    @DrawableRes public val History: Int = R.drawable.ic_history
    @DrawableRes public val Image: Int = R.drawable.ic_image
    @DrawableRes public val ImageSearch: Int = R.drawable.ic_image_search
    @DrawableRes public val Imagesmode: Int = R.drawable.ic_imagesmode
    @DrawableRes public val IndeterminateCheckBox: Int = R.drawable.ic_indeterminate_check_box
    @DrawableRes public val Info: Int = R.drawable.ic_info
    @DrawableRes public val IosShare: Int = R.drawable.ic_ios_share
    @DrawableRes public val KeyboardArrowDown: Int = R.drawable.ic_keyboard_arrow_down
    @DrawableRes public val KeyboardArrowRight: Int = R.drawable.ic_keyboard_arrow_right
    @DrawableRes public val KeyboardArrowUp: Int = R.drawable.ic_keyboard_arrow_up
    @DrawableRes public val Label: Int = R.drawable.ic_label
    @DrawableRes public val Language: Int = R.drawable.ic_language
    @DrawableRes public val Layers: Int = R.drawable.ic_layers
    @DrawableRes public val LensBlur: Int = R.drawable.ic_lens_blur
    @DrawableRes public val LightMode: Int = R.drawable.ic_light_mode
    @DrawableRes public val LinkOff: Int = R.drawable.ic_link_off
    @DrawableRes public val LocationChip: Int = R.drawable.ic_location_chip
    @DrawableRes public val LocationOff: Int = R.drawable.ic_location_off
    @DrawableRes public val LocationOn: Int = R.drawable.ic_location_on
    @DrawableRes public val Lock: Int = R.drawable.ic_lock
    @DrawableRes public val LockOpen: Int = R.drawable.ic_lock_open
    @DrawableRes public val ManageHistory: Int = R.drawable.ic_manage_history
    @DrawableRes public val ManageSearch: Int = R.drawable.ic_manage_search
    @DrawableRes public val Map: Int = R.drawable.ic_map
    @DrawableRes public val Memory: Int = R.drawable.ic_memory
    @DrawableRes public val MoreVert: Int = R.drawable.ic_more_vert
    @DrawableRes public val MotionPhotosOn: Int = R.drawable.ic_motion_photos_on
    @DrawableRes public val MyLocation: Int = R.drawable.ic_my_location
    @DrawableRes public val NearMe: Int = R.drawable.ic_near_me
    @DrawableRes public val NoPhotography: Int = R.drawable.ic_no_photography
    @DrawableRes public val NorthEast: Int = R.drawable.ic_north_east
    @DrawableRes public val Numbers: Int = R.drawable.ic_numbers
    @DrawableRes public val OpenInNew: Int = R.drawable.ic_open_in_new
    @DrawableRes public val Palette: Int = R.drawable.ic_palette
    @DrawableRes public val Person: Int = R.drawable.ic_person
    @DrawableRes public val PhotoCamera: Int = R.drawable.ic_photo_camera
    @DrawableRes public val PhotoLibrary: Int = R.drawable.ic_photo_library
    @DrawableRes public val PhotoSizeSelectLarge: Int = R.drawable.ic_photo_size_select_large
    @DrawableRes public val PictureAsPdf: Int = R.drawable.ic_picture_as_pdf
    @DrawableRes public val PinDrop: Int = R.drawable.ic_pin_drop
    @DrawableRes public val Plagiarism: Int = R.drawable.ic_plagiarism
    @DrawableRes public val PlaylistAddCheck: Int = R.drawable.ic_playlist_add_check
    @DrawableRes public val Policy: Int = R.drawable.ic_policy
    @DrawableRes public val PrivacyTip: Int = R.drawable.ic_privacy_tip
    @DrawableRes public val Public: Int = R.drawable.ic_public
    @DrawableRes public val RadioButtonUnchecked: Int = R.drawable.ic_radio_button_unchecked
    @DrawableRes public val RawOff: Int = R.drawable.ic_raw_off
    @DrawableRes public val RawOn: Int = R.drawable.ic_raw_on
    @DrawableRes public val Redo: Int = R.drawable.ic_redo
    @DrawableRes public val Refresh: Int = R.drawable.ic_refresh
    @DrawableRes public val Remove: Int = R.drawable.ic_remove
    @DrawableRes public val Rule: Int = R.drawable.ic_rule
    @DrawableRes public val SatelliteAlt: Int = R.drawable.ic_satellite_alt
    @DrawableRes public val Save: Int = R.drawable.ic_save
    @DrawableRes public val Schedule: Int = R.drawable.ic_schedule
    @DrawableRes public val ScheduleSend: Int = R.drawable.ic_schedule_send
    @DrawableRes public val Search: Int = R.drawable.ic_search
    @DrawableRes public val Security: Int = R.drawable.ic_security
    @DrawableRes public val SelectAll: Int = R.drawable.ic_select_all
    @DrawableRes public val SelectCheckBox: Int = R.drawable.ic_select_check_box
    @DrawableRes public val Sell: Int = R.drawable.ic_sell
    @DrawableRes public val Settings: Int = R.drawable.ic_settings
    @DrawableRes public val SettingsBackupRestore: Int = R.drawable.ic_settings_backup_restore
    @DrawableRes public val Share: Int = R.drawable.ic_share
    @DrawableRes public val ShareLocation: Int = R.drawable.ic_share_location
    @DrawableRes public val Shield: Int = R.drawable.ic_shield
    @DrawableRes public val ShutterSpeed: Int = R.drawable.ic_shutter_speed
    @DrawableRes public val Sort: Int = R.drawable.ic_sort
    @DrawableRes public val Star: Int = R.drawable.ic_star
    @DrawableRes public val StarHalf: Int = R.drawable.ic_star_half
    @DrawableRes public val Storage: Int = R.drawable.ic_storage
    @DrawableRes public val Straighten: Int = R.drawable.ic_straighten
    @DrawableRes public val SwapVert: Int = R.drawable.ic_swap_vert
    @DrawableRes public val Sync: Int = R.drawable.ic_sync
    @DrawableRes public val TableView: Int = R.drawable.ic_table_view
    @DrawableRes public val Tag: Int = R.drawable.ic_tag
    @DrawableRes public val TextFields: Int = R.drawable.ic_text_fields
    @DrawableRes public val Timelapse: Int = R.drawable.ic_timelapse
    @DrawableRes public val Timeline: Int = R.drawable.ic_timeline
    @DrawableRes public val Timer: Int = R.drawable.ic_timer
    @DrawableRes public val TravelExplore: Int = R.drawable.ic_travel_explore
    @DrawableRes public val Tune: Int = R.drawable.ic_tune
    @DrawableRes public val Undo: Int = R.drawable.ic_undo
    @DrawableRes public val Update: Int = R.drawable.ic_update
    @DrawableRes public val UploadFile: Int = R.drawable.ic_upload_file
    @DrawableRes public val Verified: Int = R.drawable.ic_verified
    @DrawableRes public val ViewList: Int = R.drawable.ic_view_list
    @DrawableRes public val Visibility: Int = R.drawable.ic_visibility
    @DrawableRes public val VisibilityOff: Int = R.drawable.ic_visibility_off
    @DrawableRes public val Warning: Int = R.drawable.ic_warning
    @DrawableRes public val WifiOff: Int = R.drawable.ic_wifi_off
    @DrawableRes public val WrongLocation: Int = R.drawable.ic_wrong_location
    @DrawableRes public val ZoomIn: Int = R.drawable.ic_zoom_in
    @DrawableRes public val ZoomOut: Int = R.drawable.ic_zoom_out
}

/** An icon from [ExifIcons]. Pass a [contentDescription] for icons that carry meaning on their own. */
@Composable
public fun ExifIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(painter = painterResource(icon), contentDescription = contentDescription, modifier = modifier, tint = tint)
}
