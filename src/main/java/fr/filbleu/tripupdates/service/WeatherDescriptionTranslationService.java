package fr.filbleu.tripupdates.service;

import org.springframework.stereotype.Service;

@Service
public class WeatherDescriptionTranslationService {

    public String toFrench(int weatherCode) {
        return switch (weatherCode) {
            case 0 -> "Ciel dégagé";
            case 1 -> "Principalement dégagé";
            case 2 -> "Partiellement nuageux";
            case 3 -> "Couvert";
            case 45, 48 -> "Brouillard";
            case 51, 53, 55 -> "Bruine";
            case 56, 57 -> "Bruine verglaçante";
            case 61, 63, 65 -> "Pluie";
            case 66, 67 -> "Pluie verglaçante";
            case 71, 73, 75 -> "Chutes de neige";
            case 77 -> "Grains de neige";
            case 80, 81, 82 -> "Averses de pluie";
            case 85, 86 -> "Averses de neige";
            case 95 -> "Orage";
            case 96, 99 -> "Orage avec grêle";
            default -> "Conditions inconnues";
        };
    }
}
