using System.Windows;
using PhoneLink.Desktop.ViewModels;

namespace PhoneLink.Desktop;

public partial class PairingWindow : Window
{
    private PairingWindowViewModel? _viewModel;

    public PairingWindow(PairingWindowViewModel viewModel)
    {
        InitializeComponent();
        DataContext = viewModel;
        _viewModel = viewModel;
        Closed += (_, _) => viewModel.Dispose();
    }

    private void OnCloseClick(object sender, RoutedEventArgs e)
    {
        Close();
    }
}