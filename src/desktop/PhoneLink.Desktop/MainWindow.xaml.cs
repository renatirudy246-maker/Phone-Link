using System.Windows;
using PhoneLink.Desktop.ViewModels;

namespace PhoneLink.Desktop;

public partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
        DataContext = new MainViewModel();
    }
}